/*
 * ****************************************************************************
 * Copyright (c) 2013, Daniel Murphy All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted
 * provided that the following conditions are met:
 * * Redistributions of source code must retain the above copyright notice, this list of conditions
 *   and the following disclaimer.
 * * Redistributions in binary form must reproduce the above copyright notice, this list of
 *   conditions and the following disclaimer in the documentation and/or other materials provided
 *   with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
 * FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY
 * WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 * ****************************************************************************
 */
package org.jbox2d.collision.broadphase

import org.jbox2d.callbacks.DebugDraw
import org.jbox2d.callbacks.PairCallback
import org.jbox2d.callbacks.TreeCallback
import org.jbox2d.callbacks.TreeRayCastCallback
import org.jbox2d.collision.AABB
import org.jbox2d.collision.RayCastInput
import org.jbox2d.common.Vec2

/**
 * The broad-phase is used for computing pairs and performing volume queries and ray casts. This
 * broad-phase does not persist pairs. Instead, this reports potentially new pairs. It is up to the
 * client to consume the new pairs and to track subsequent overlap.
 *
 * @author Daniel Murphy
 */
class BroadPhase(strategy: BroadPhaseStrategy) : TreeCallback {
  private val m_tree: BroadPhaseStrategy

  private var m_proxyCount = 0
  private var m_moveBuffer: IntArray
  private var m_moveCapacity: Int
  private var m_moveCount: Int
  private var m_pairBuffer: Array<Pair>
  private var m_pairCapacity = 16
  private var m_pairCount = 0
  private var m_queryProxyId: Int

  init {
    m_pairBuffer = Array<Pair>(m_pairCapacity) { Pair() }
    m_moveCapacity = 16
    m_moveCount = 0
    m_moveBuffer = IntArray(m_moveCapacity)
    m_tree = strategy
    m_queryProxyId = NULL_PROXY
  }

  /**
   * Create a proxy with an initial AABB. Pairs are not reported until updatePairs is called.
   *
   * @param aabb
   * @param userData
   * @return
   */
  fun createProxy(aabb: AABB, userData: Any): Int {
    val proxyId = m_tree.createProxy(aabb, userData)
    ++m_proxyCount
    bufferMove(proxyId)
    return proxyId
  }

  /**
   * Destroy a proxy. It is up to the client to remove any pairs.
   *
   * @param proxyId
   */
  fun destroyProxy(proxyId: Int) {
    unbufferMove(proxyId)
    --m_proxyCount
    m_tree.destroyProxy(proxyId)
  }

  /**
   * Call MoveProxy as many times as you like, then when you are done call UpdatePairs to finalized
   * the proxy pairs (for your time step).
   */
  fun moveProxy(proxyId: Int, aabb: AABB, displacement: Vec2) {
    val buffer = m_tree.moveProxy(proxyId, aabb, displacement)
    if (buffer) {
      bufferMove(proxyId)
    }
  }

  fun touchProxy(proxyId: Int) {
    bufferMove(proxyId)
  }

  fun getUserData(proxyId: Int): Any {
    return m_tree.getUserData(proxyId)
  }

  fun getFatAABB(proxyId: Int): AABB {
    return m_tree.getFatAABB(proxyId)
  }

  fun testOverlap(proxyIdA: Int, proxyIdB: Int): Boolean {
    // return AABB.testOverlap(proxyA.aabb, proxyB.aabb);
    val a = m_tree.getFatAABB(proxyIdA)
    val b = m_tree.getFatAABB(proxyIdB)
    if (b.lowerBound.x - a.upperBound.x > 0.0f || b.lowerBound.y - a.upperBound.y > 0.0f) {
      return false
    }
    if (a.lowerBound.x - b.upperBound.x > 0.0f || a.lowerBound.y - b.upperBound.y > 0.0f) {
      return false
    }
    return true
  }

  /**
   * Get the number of proxies.
   *
   * @return
   */
  fun getProxyCount(): Int {
    return m_proxyCount
  }

  fun drawTree(argDraw: DebugDraw) {
    m_tree.drawTree(argDraw)
  }

  /**
   * Update the pairs. This results in pair callbacks. This can only add pairs.
   *
   * @param callback
   */
  fun updatePairs(callback: PairCallback) {
    // log.debug("beginning to update pairs");
    // Reset pair buffer
    m_pairCount = 0

    // Perform tree queries for all moving proxies.
    for (i in 0 until m_moveCount) {
      m_queryProxyId = m_moveBuffer[i]
      if (m_queryProxyId == NULL_PROXY) {
        continue
      }

      // We have to query the tree with the fat AABB so that
      // we don't fail to create a pair that may touch later.
      val fatAABB = m_tree.getFatAABB(m_queryProxyId)

      // Query tree, create pairs and add them pair buffer.
      // log.debug("quering aabb: "+m_queryProxy.aabb);
      m_tree.query(this, fatAABB)
    }
    // log.debug("Number of pairs found: "+m_pairCount);

    // Reset move buffer
    m_moveCount = 0

    // Sort the pair buffer to expose duplicates.
    m_pairBuffer.sort(0, m_pairCount)

    // Send the pairs back to the client.
    var i = 0
    while (i < m_pairCount) {
      val primaryPair = m_pairBuffer[i]
      val userDataA = m_tree.getUserData(primaryPair.proxyIdA)
      val userDataB = m_tree.getUserData(primaryPair.proxyIdB)

      // log.debug("returning pair: "+userDataA+", "+userDataB);
      callback.addPair(userDataA, userDataB)
      ++i

      // Skip any duplicate pairs.
      while (i < m_pairCount) {
        val pair = m_pairBuffer[i]
        if (pair.proxyIdA != primaryPair.proxyIdA || pair.proxyIdB != primaryPair.proxyIdB) {
          break
        }
        // log.debug("skipping duplicate");
        ++i
      }
    }

    // Try to keep the tree balanced.
    // m_tree.rebalance(Settings.TREE_REBALANCE_STEPS);
  }

  /**
   * Query an AABB for overlapping proxies. The callback class is called for each proxy that
   * overlaps the supplied AABB.
   *
   * @param callback
   * @param aabb
   */
  fun query(callback: TreeCallback, aabb: AABB) {
    m_tree.query(callback, aabb)
  }

  /**
   * Ray-cast against the proxies in the tree. This relies on the callback to perform a exact
   * ray-cast in the case were the proxy contains a shape. The callback also performs the any
   * collision filtering. This has performance roughly equal to k * log(n), where k is the number of
   * collisions and n is the number of proxies in the tree.
   *
   * @param input the ray-cast input data. The ray extends from p1 to p1 + maxFraction * (p2 - p1).
   * @param callback a callback class that is called for each proxy that is hit by the ray.
   */
  fun raycast(callback: TreeRayCastCallback, input: RayCastInput) {
    m_tree.raycast(callback, input)
  }

  /**
   * Get the height of the embedded tree.
   *
   * @return
   */
  fun getTreeHeight(): Int {
    return m_tree.computeHeight()
  }

  fun getTreeBalance(): Int {
    return m_tree.getMaxBalance()
  }

  fun getTreeQuality(): Float {
    return m_tree.getAreaRatio()
  }

  protected fun bufferMove(proxyId: Int) {
    if (m_moveCount == m_moveCapacity) {
      val old = m_moveBuffer
      m_moveCapacity *= 2
      m_moveBuffer = IntArray(m_moveCapacity)
      old.copyInto(m_moveBuffer)
    }
    m_moveBuffer[m_moveCount] = proxyId
    ++m_moveCount
  }

  protected fun unbufferMove(proxyId: Int) {
    for (i in 0 until m_moveCount) {
      if (m_moveBuffer[i] == proxyId) {
        m_moveBuffer[i] = NULL_PROXY
      }
    }
  }

  // private final PairStack pairStack = new PairStack();
  /** This is called from DynamicTree::query when we are gathering pairs. */
  override fun treeCallback(proxyId: Int): Boolean {
    // A proxy cannot form a pair with itself.
    if (proxyId == m_queryProxyId) {
      // log.debug("It was us...");
      return true
    }

    // Grow the pair buffer as needed.
    if (m_pairCount == m_pairCapacity) {
      val oldBuffer = m_pairBuffer
      m_pairCapacity *= 2
      m_pairBuffer = Array<Pair>(m_pairCapacity) { Pair() }
      oldBuffer.copyInto(m_pairBuffer)
    }
    if (proxyId < m_queryProxyId) {
      // log.debug("new proxy is first");
      m_pairBuffer[m_pairCount].proxyIdA = proxyId
      m_pairBuffer[m_pairCount].proxyIdB = m_queryProxyId
    } else {
      // log.debug("new proxy is second");
      m_pairBuffer[m_pairCount].proxyIdA = m_queryProxyId
      m_pairBuffer[m_pairCount].proxyIdB = proxyId
    }
    ++m_pairCount
    return true
  }

  companion object {
    const val NULL_PROXY = -1
  }
}

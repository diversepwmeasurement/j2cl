/**
 * Impl hand rolled.
 */
goog.module('vmbootstrap.Objects$impl');

let Boolean = goog.forwardDeclare('gen.java.lang.Boolean$impl');
let Class = goog.forwardDeclare('gen.java.lang.Class$impl');
let Double = goog.forwardDeclare('gen.java.lang.Double$impl');
let Object = goog.forwardDeclare('gen.java.lang.Object$impl');
let String = goog.forwardDeclare('gen.java.lang.String$impl');
let Arrays = goog.forwardDeclare('vmbootstrap.Arrays$impl');
let JavaScriptObject = goog.forwardDeclare('vmbootstrap.JavaScriptObject$impl');
let $boolean = goog.forwardDeclare('vmbootstrap.primitives.$boolean$impl');
let $double = goog.forwardDeclare('vmbootstrap.primitives.$double$impl');
let $Hashing = goog.forwardDeclare('nativebootstrap.Hashing$impl');

/**
 * Provides devirtualized Object methods
 */
class Objects {
  /**
   * @param {*} obj
   * @param {*} other
   * @return {boolean}
   * @public
   */
  static m_equals__java_lang_Object__java_lang_Object(obj, other) {
    Objects.$clinit();

    // Objects: use the custom 'equals' if it exists.
    if (obj instanceof Object) {
      return obj.m_equals__java_lang_Object(other);
    } else if (obj.equals) {
      return obj.equals(other);
    }

    // Boxed Types: overrides 'equals' but doesn't need special casing as
    // fallback covers them.

    // Array Types: doesn't override 'equals'.

    // Fallback to default j.l.Object#equals behavior.
    return obj === other;
  }

  /**
   * @param {*} obj
   * @return {number}
   * @public
   */
  static m_hashCode__java_lang_Object(obj) {
    Objects.$clinit();

    // Objects: use the custom 'hashCode' if it exists.
    if (obj instanceof Object) {
      return obj.m_hashCode();
    } else if (obj.hashCode) {
      return obj.hashCode();
    }

    // Boxed Types: overrides 'hashCode. Restore the behavior by the overrides
    // in boxing classes.
    let type = typeof obj;
    if (type == 'number') {
      return Double.m_hashCode__java_lang_Double(/**@type {number}*/ (obj));
    } else if (type == 'boolean') {
      return Boolean.m_hashCode__java_lang_Boolean(/**@type {boolean}*/ (obj));
    } else if (type == 'string') {
      return String.m_hashCode__java_lang_String(/**@type {string}*/ (obj));
    }

    // Array Types: doesn't override 'hashCode'.

    // Fallback to default j.l.Object#hashCode behavior.
    return $Hashing.$getHashCode(obj);
  }

  /**
   * @param {*} obj
   * @return {?string}
   * @public
   */
  static m_toString__java_lang_Object(obj) {
    Objects.$clinit();

    // We only special case 'toString' for arrays to enforce the Java behavior.
    if (obj instanceof Array) {
      return Arrays.m_toString__java_lang_Object(obj);
    }

    // For the rest including Java objects, 'toString' already has the behavior
    // we want.
    return obj.toString();
  }

  /**
   * @param {*} obj
   * @return {Class}
   * @public
   */
  static m_getClass__java_lang_Object(obj) {
    Objects.$clinit();

    // We special case 'getClass' for all types as they all corresspond to
    // different classes.
    let type = typeof obj;
    if (type == 'number') {
      return $double.$getClass();
    } else if (type == 'boolean') {
      return $boolean.$getClass();
    } else if (type == 'string') {
      return String.$getClass();
    } else if (obj instanceof Array) {
      return Arrays.m_getClass__java_lang_Object(obj);
    } else if (obj instanceof Object) {
      return obj.m_getClass();
    } else {
      // Do not need to check existence of 'getClass' since j.l.Object#getClass
      // is final and all native types map to a single special class.
      return JavaScriptObject.m_getClass__Object(obj);
    }
  }

  /**
   * Runs inline static field initializers.
   * @public
   */
  static $clinit() {
    Object = goog.module.get('gen.java.lang.Object$impl');
    Boolean = goog.module.get('gen.java.lang.Boolean$impl');
    Class = goog.module.get('gen.java.lang.Class$impl');
    Double = goog.module.get('gen.java.lang.Double$impl');
    String = goog.module.get('gen.java.lang.String$impl');
    Arrays = goog.module.get('vmbootstrap.Arrays$impl');
    JavaScriptObject = goog.module.get('vmbootstrap.JavaScriptObject$impl');
    $boolean = goog.module.get('vmbootstrap.primitives.$boolean$impl');
    $double = goog.module.get('vmbootstrap.primitives.$double$impl');
    $Hashing = goog.module.get('nativebootstrap.Hashing$impl');
  }
};


/**
 * Exported class.
 */
exports = Objects;

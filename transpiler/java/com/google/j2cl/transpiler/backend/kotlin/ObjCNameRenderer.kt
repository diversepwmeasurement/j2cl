/*
 * Copyright 2022 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.j2cl.transpiler.backend.kotlin

import com.google.j2cl.common.InternalCompilerError
import com.google.j2cl.common.StringUtils
import com.google.j2cl.transpiler.ast.ArrayTypeDescriptor
import com.google.j2cl.transpiler.ast.DeclaredTypeDescriptor
import com.google.j2cl.transpiler.ast.FieldDescriptor
import com.google.j2cl.transpiler.ast.Method
import com.google.j2cl.transpiler.ast.MethodDescriptor
import com.google.j2cl.transpiler.ast.PrimitiveTypeDescriptor
import com.google.j2cl.transpiler.ast.PrimitiveTypes
import com.google.j2cl.transpiler.ast.TypeDeclaration
import com.google.j2cl.transpiler.ast.TypeDescriptor
import com.google.j2cl.transpiler.ast.TypeDescriptors.isJavaLangObject
import com.google.j2cl.transpiler.ast.TypeVariable
import com.google.j2cl.transpiler.ast.Variable
import com.google.j2cl.transpiler.ast.Visibility
import com.google.j2cl.transpiler.backend.kotlin.common.camelCaseStartsWith
import com.google.j2cl.transpiler.backend.kotlin.common.letIf
import com.google.j2cl.transpiler.backend.kotlin.common.mapFirst
import com.google.j2cl.transpiler.backend.kotlin.source.Source
import com.google.j2cl.transpiler.backend.kotlin.source.commaSeparated
import com.google.j2cl.transpiler.backend.kotlin.source.ifNotNullSource
import com.google.j2cl.transpiler.backend.kotlin.source.inRoundBrackets
import com.google.j2cl.transpiler.backend.kotlin.source.join
import com.google.j2cl.transpiler.backend.kotlin.source.source
import com.google.j2cl.transpiler.backend.kotlin.source.sourceIf

internal fun Renderer.optInExperimentalObjCNameFileAnnotationSource(): Source =
  join(
    source("@file:"),
    topLevelQualifiedNameSource("kotlin.OptIn"),
    inRoundBrackets(
      join(
        topLevelQualifiedNameSource("kotlin.experimental.ExperimentalObjCName"),
        source("::class")
      )
    )
  )

internal fun Renderer.objCNameAnnotationSource(name: String, exact: Boolean? = null): Source =
  join(
    at(topLevelQualifiedNameSource("kotlin.native.ObjCName")),
    inRoundBrackets(
      commaSeparated(
        literalSource(name),
        exact.ifNotNullSource { parameterSource("exact", literalSource(it)) }
      )
    )
  )

internal fun Renderer.objCNameAnnotationSource(typeDeclaration: TypeDeclaration): Source =
  sourceIf(typeDeclaration.needsObjCNameAnnotation) {
    objCNameAnnotationSource(typeDeclaration.objCName, exact = true)
  }

internal fun Renderer.objCNameAnnotationSource(
  methodDescriptor: MethodDescriptor,
  methodObjCNames: MethodObjCNames?
): Source =
  sourceIf(!methodDescriptor.isConstructor) {
    methodObjCNames?.methodName.ifNotNullSource { objCNameAnnotationSource(it, exact = false) }
  }

internal fun Renderer.objCNameAnnotationSource(fieldDescriptor: FieldDescriptor): Source =
  sourceIf(fieldDescriptor.needsObjCNameAnnotations) {
    objCNameAnnotationSource(fieldDescriptor.objCName, exact = false)
  }

private fun parameterSource(name: String, valueSource: Source): Source =
  assignment(source(name), valueSource)

internal data class MethodObjCNames(
  val methodName: String? = null,
  val parameterNames: List<String>
)

internal fun Method.toObjCNames(): MethodObjCNames? =
  if (!descriptor.needsObjCNameAnnotations) null
  else if (descriptor.isConstructor) toConstructorObjCNames() else toNonConstructorObjCNames()

private val MethodDescriptor.needsObjCNameAnnotations
  get() = visibility.needsObjCNameAnnotation && !isKtOverride

private val FieldDescriptor.needsObjCNameAnnotations
  get() =
    enclosingTypeDescriptor.typeDeclaration.needsObjCNameAnnotation &&
      visibility.needsObjCNameAnnotation &&
      isStatic

private val TypeDeclaration.needsObjCNameAnnotation
  get() = visibility.needsObjCNameAnnotation && !isLocal

private val Visibility.needsObjCNameAnnotation
  get() = this == Visibility.PUBLIC || this == Visibility.PROTECTED

private fun Method.toConstructorObjCNames(): MethodObjCNames =
  descriptor.objectiveCName.let { objectiveCName ->
    MethodObjCNames(
      objectiveCName,
      if (objectiveCName != null) {
        objectiveCName.objCMethodParameterNames.mapFirst {
          val prefix = "initWith"
          if (it.startsWith(prefix)) it.substring(prefix.length) else parameters.first().objCName
        }
      } else {
        parameters.mapIndexed { index, parameter ->
          parameter.objCName.letIf(index != 0) { "with$it" }
        }
      }
    )
  }

private fun Method.toNonConstructorObjCNames(): MethodObjCNames =
  descriptor.objectiveCName.let { objectiveCName ->
    if (objectiveCName == null || parameters.isEmpty()) {
      MethodObjCNames(objectiveCName, parameters.map { "with${it.objCName}" })
    } else {
      val objCParameterNames = objectiveCName.objCMethodParameterNames
      val firstObjCParameterName = objCParameterNames.firstOrNull()
      if (firstObjCParameterName == null) {
        MethodObjCNames(objectiveCName, objCParameterNames)
      } else {
        // Split string by index of last uppercase or in half arbitrarily. Does not
        // handle single character objc name.
        check(firstObjCParameterName.length > 1)
        val splitIndex =
          firstObjCParameterName.indexOfLast { it.isUpperCase() }.takeIf { it > 0 }
            ?: (firstObjCParameterName.length / 2)
        MethodObjCNames(
          firstObjCParameterName.substring(0, splitIndex),
          objCParameterNames.mapFirst { it.substring(splitIndex) }
        )
      }
    }
  }

private val String.objCMethodParameterNames: List<String>
  get() = letIf(lastOrNull() == ':') { dropLast(1) }.split(":")

internal val TypeDeclaration.objCName: String
  get() = objCName(forMember = false)

internal fun TypeDeclaration.objCName(forMember: Boolean): String =
  objectiveCName ?: mappedObjCName ?: defaultObjCName(forMember = forMember)

private val TypeDeclaration.mappedObjCName: String?
  get() =
    when (qualifiedBinaryName) {
      "java.lang.Object" -> "NSObject"
      "java.lang.String" -> "NSString"
      "java.lang.Class" -> "IOSClass"
      "java.lang.Number" -> "NSNumber"
      "java.lang.Cloneable" -> "NSCopying"
      else -> null
    }

internal fun TypeDeclaration.defaultObjCName(forMember: Boolean): String =
  objCNamePrefix(forMember) + simpleObjCName

private fun TypeDeclaration.objCNamePrefix(forMember: Boolean) =
  enclosingTypeDeclaration.run {
    if (this != null) objCName(forMember = forMember) + "_"
    else simpleObjCNamePrefix(forMember = forMember)
  }

private fun TypeDeclaration.simpleObjCNamePrefix(forMember: Boolean) =
  objectiveCNamePrefix ?: objCPackagePrefix(forMember = forMember)

private val TypeDeclaration.simpleObjCName: String
  get() = simpleSourceName.objCName

private fun TypeDeclaration.objCPackagePrefix(forMember: Boolean): String =
  packageName?.objCPackagePrefix(forMember = forMember) ?: ""

private fun String.objCPackagePrefix(forMember: Boolean): String =
  this
    // TODO(b/265295531): This line is a temporary hack, remove when not needed.
    .letIf(!forMember && (startsWith("java.") || startsWith("javax."))) { "j2kt.$it" }
    .split('.')
    .joinToString(separator = "") { it.titleCase.objCName }

internal val String.titleCase
  get() = StringUtils.capitalize(this)

internal val String.objCName
  get() = replace('$', '_')

private const val idObjCName = "id"

internal fun TypeDescriptor.objCName(useId: Boolean, forMember: Boolean): String =
  when (this) {
    is PrimitiveTypeDescriptor -> primitiveObjCName
    is ArrayTypeDescriptor -> arrayObjCName(forMember = forMember)
    is DeclaredTypeDescriptor -> declaredObjCName(useId = useId, forMember = forMember)
    is TypeVariable -> variableObjCName(useId = useId, forMember = forMember)
    else -> idObjCName
  }

private val PrimitiveTypeDescriptor.primitiveObjCName: String
  get() =
    when (this) {
      PrimitiveTypes.VOID -> "void"
      PrimitiveTypes.BOOLEAN -> "boolean"
      PrimitiveTypes.BYTE -> "byte"
      PrimitiveTypes.SHORT -> "short"
      PrimitiveTypes.INT -> "int"
      PrimitiveTypes.LONG -> "long"
      PrimitiveTypes.CHAR -> "char"
      PrimitiveTypes.FLOAT -> "float"
      PrimitiveTypes.DOUBLE -> "double"
      else -> throw InternalCompilerError("Unexpected ${this::class.java.simpleName}")
    }

private fun DeclaredTypeDescriptor.declaredObjCName(useId: Boolean, forMember: Boolean): String =
  if (useId && isJavaLangObject(this)) idObjCName
  else typeDeclaration.objCName(forMember = forMember)

private fun ArrayTypeDescriptor.arrayObjCName(forMember: Boolean): String =
  leafTypeDescriptor.objCName(useId = false, forMember = forMember) + "Array" + dimensionsSuffix

private val ArrayTypeDescriptor.dimensionsSuffix
  get() = if (dimensions > 1) "$dimensions" else ""

private fun TypeVariable.variableObjCName(useId: Boolean, forMember: Boolean): String =
  upperBoundTypeDescriptor.objCName(useId = useId, forMember = forMember)

private val Variable.objCName
  get() = typeDescriptor.objCName(useId = true, forMember = true).titleCase

internal val FieldDescriptor.objCName: String
  get() =
    name!!
      .objCName
      .let { name ->
        if (objCReservedPrefixes.any { name.camelCaseStartsWith(it) }) "the" + name.titleCase
        else name
      }
      .let { name ->
        if (reservedKeywords.contains(name)) {
          name + "__"
        } else name
      }

// Taken from
// "google3/third_party/java_src/j2objc/translator/src/main/resources/com/google/devtools/j2objc/reserved_names.txt"
private val reservedKeywords =
  setOf(
    // types
    "id",
    "bool",
    "BOOL",
    "SEL",
    "IMP",
    "unichar",

    // constants
    "nil",
    "Nil",
    "YES",
    "NO",
    "TRUE",
    "FALSE",

    // J2ObjC types (J2ObjC_types.h)
    "jbyte",
    "jchar",
    "jshort",
    "jint",
    "jlong",
    "jfloat",
    "jdouble",
    "jboolean",
    "volatile_jbyte",
    "volatile_jchar",
    "volatile_jshort",
    "volatile_jint",
    "volatile_jlong",
    "volatile_jfloat",
    "volatile_jdouble",
    "volatile_jboolean",
    "volatile_id",

    // C99 keywords
    "auto",
    "const",
    "entry",
    "extern",
    "goto",
    "inline",
    "register",
    "restrict",
    "signed",
    "sizeof",
    "struct",
    "typedef",
    "union",
    "unsigned",
    "volatile",

    // GNU C keyword
    "typeof",

    // C++ keywords
    "and",
    "and_eq",
    "asm",
    "bitand",
    "bitor",
    "compl",
    "const_cast",
    "delete",
    "dynamic_cast",
    "explicit",
    "export",
    "friend",
    "mutable",
    "namespace",
    "not",
    "not_eq",
    "operator",
    "or",
    "or_eq",
    "reinterpret_cast",
    "static_cast",
    "template",
    "typeid",
    "typename",
    "using",
    "virtual",
    "wchar_t",
    "xor",
    "xor_eq",

    // variables
    "self",
    "isa",

    // Definitions from stddef.h
    "ptrdiff_t",
    "size_t",
    "wchar_t",
    "wint_t",

    // Definitions from stdint.h
    "int8_t",
    "int16_t",
    "int32_t",
    "int64_t",
    "uint8_t",
    "uint16_t",
    "uint32_t",
    "uint64_t",
    "int_least8_t",
    "int_least16_t",
    "int_least32_t",
    "int_least64_t",
    "uint_least8_t",
    "uint_least16_t",
    "uint_least32_t",
    "uint_least64_t",
    "int_fast8_t",
    "int_fast16_t",
    "int_fast32_t",
    "int_fast64_t",
    "uint_fast8_t",
    "uint_fast16_t",
    "uint_fast32_t",
    "uint_fast64_t",
    "intptr_t",
    "uintptr_t",
    "intmax_t",
    "uintmax_t",
    "INT8_MAX",
    "INT16_MAX",
    "INT32_MAX",
    "INT64_MAX",
    "INT8_MIN",
    "INT16_MIN",
    "INT32_MIN",
    "INT64_MIN",
    "UINT8_MAX",
    "UINT16_MAX",
    "UINT32_MAX",
    "UINT64_MAX",
    "INT_LEAST8_MIN",
    "INT_LEAST16_MIN",
    "INT_LEAST32_MIN",
    "INT_LEAST64_MIN",
    "INT_LEAST8_MAX",
    "INT_LEAST16_MAX",
    "INT_LEAST32_MAX",
    "INT_LEAST64_MAX",
    "INT_FAST8_MIN",
    "INT_FAST16_MIN",
    "INT_FAST32_MIN",
    "INT_FAST64_MIN",
    "INT_FAST8_MAX",
    "INT_FAST16_MAX",
    "INT_FAST32_MAX",
    "INT_FAST64_MAX",
    "UINT_FAST8_MAX",
    "UINT_FAST16_MAX",
    "UINT_FAST32_MAX",
    "UINT_FAST64_MAX",
    "INTPTR_MIN",
    "INTPTR_MAX",
    "UINTPTR_MAX",
    "INTMAX_MIN",
    "INTMAX_MAX",
    "UINTMAX_MAX",
    "PTRDIFF_MIN",
    "PTRDIFF_MAX",
    "SIZE_MAX",
    "WCHAR_MAX",
    "WCHAR_MIN",
    "WINT_MIN",
    "WINT_MAX",
    "SIG_ATOMIC_MIN",
    "SIG_ATOMIC_MAX",
    "INT8_MAX",
    "INT16_MAX",
    "INT32_MAX",
    "INT64_MAX",
    "UINT8_C",
    "UINT16_C",
    "UINT32_C",
    "UINT64_C",
    "INTMAX_C",
    "UINTMAX_C",

    // Definitions from stdio.h
    "va_list",
    "fpos_t",
    "FILE",
    "off_t",
    "ssize_t",
    "BUFSIZ",
    "EOF",
    "FOPEN_MAX",
    "FILENAME_MAX",
    "R_OK",
    "SEEK_SET",
    "SEEK_CUR",
    "SEEK_END",
    "stdin",
    "STDIN_FILENO",
    "stdout",
    "STDOUT_FILENO",
    "stderr",
    "STDERR_FILENO",
    "TMP_MAX",
    "W_OK",
    "X_OK",
    "sys_errlist",

    // Definitions from stdlib.h
    "ct_rune_t",
    "rune_t",
    "div_t",
    "ldiv_t",
    "lldiv_t",
    "dev_t",
    "mode_t",
    "NULL",
    "EXIT_FAILURE",
    "EXIT_SUCCESS",
    "RAND_MAX",
    "MB_CUR_MAX",
    "MB_CUR_MAX_L",

    // Definitions from errno.h
    "errno",
    "EPERM",
    "ENOENT",
    "ESRCH",
    "EINTR",
    "EIO",
    "ENXIO",
    "E2BIG",
    "ENOEXEC",
    "EBADF",
    "ECHILD",
    "EDEADLK",
    "ENOMEM",
    "EACCES",
    "EFAULT",
    "ENOTBLK",
    "EBUSY",
    "EEXIST",
    "EXDEV",
    "ENODEV",
    "ENOTDIR",
    "EISDIR",
    "EINVAL",
    "ENFILE",
    "EMFILE",
    "ENOTTY",
    "ETXTBSY",
    "EFBIG",
    "ENOSPC",
    "ESPIPE",
    "EROFS",
    "EMLINK",
    "EPIPE",
    "EDOM",
    "ERANGE",
    "EAGAIN",
    "EWOULDBLOCK",
    "EINPROGRESS",
    "EALREADY",
    "ENOTSOCK",
    "EDESTADDRREQ",
    "EMSGSIZE",
    "EPROTOTYPE",
    "ENOPROTOOPT",
    "EPROTONOSUPPORT",
    "ESOCKTNOSUPPORT",
    "ENOTSUP",
    "ENOTSUPP",
    "EPFNOSUPPORT",
    "EAFNOSUPPORT",
    "EADDRINUSE",
    "EADDRNOTAVAIL",
    "ENETDOWN",
    "ENETUNREACH",
    "ENETRESET",
    "ECONNABORTED",
    "ECONNRESET",
    "ENOBUFS",
    "EISCONN",
    "ENOTCONN",
    "ESHUTDOWN",
    "ETOOMANYREFS",
    "ETIMEDOUT",
    "ECONNREFUSED",
    "ELOOP",
    "ENAMETOOLONG",
    "EHOSTDOWN",
    "EHOSTUNREACH",
    "ENOTEMPTY",
    "EPROCLIM",
    "EUSERS",
    "EDQUOT",
    "ESTALE",
    "EREMOTE",
    "EBADRPC",
    "ERPCMISMATCH",
    "EPROGUNAVAIL",
    "EPROGMISMATCH",
    "EPROCUNAVAIL",
    "ENOLCK",
    "ENOSYS",
    "EFTYPE",
    "EAUTH",
    "ENEEDAUTH",
    "EPWROFF",
    "EDEVERR",
    "EOVERFLOW",
    "EBADEXEC",
    "EBADARCH",
    "ESHLIBVERS",
    "EBADMACHO",
    "ECANCELED",
    "EIDRM",
    "ENOMSG",
    "ENOATTR",
    "EBADMSG",
    "EMULTIHOP",
    "ENODATA",
    "ENOLINK",
    "ENOSR",
    "ENOSTR",
    "EPROTO",
    "ETIME",
    "ENOPOLICY",
    "ENOTRECOVERABLE",
    "EOWNERDEAD",
    "EQFULL",
    "EILSEQ",
    "EOPNOTSUPP",
    "ELAST",

    // Definitions from fcntl.h
    "AT_REMOVEDIR",
    "AT_SYMLINK_NOFOLLOW",
    "F_DUPFD",
    "F_GETFD",
    "F_SETFD",
    "F_GETFL",
    "F_SETFL",
    "F_GETOWN",
    "F_SETOWN",
    "F_GETLK",
    "F_SETLK",
    "F_SETLKW",
    "FD_CLOEXEC",
    "F_RDLCK",
    "F_UNLCK",
    "F_WRLCK",
    "SEEK_SET",
    "SEEK_CUR",
    "SEEK_END",
    "O_RDONLY",
    "O_WRONLY",
    "O_RDWR",
    "O_ACCMODE",
    "O_NONBLOCK",
    "O_APPEND",
    "O_SYNC",
    "O_CREAT",
    "O_TRUNC",
    "O_EXCL",
    "O_NOCTTY",
    "O_NOFOLLOW",
    "O_DSYNC",

    // Definitions from float.h
    "DBL_DIG",

    // Definitions from math.h
    "DBL_EPSILON",
    "DOMAIN",
    "HUGE",
    "INFINITY",
    "M_1_PI",
    "M_E",
    "M_PI",
    "M_PI_2",
    "M_PI_4",
    "M_SQRT1_2",
    "M_SQRT2",
    "NAN",
    "OVERFLOW",
    "SING",
    "UNDERFLOW",
    "signgam",

    // Definitions from mman.h
    "MAP_FIXED",
    "MAP_PRIVATE",
    "MAP_SHARED",
    "MCL_CURRENT",
    "MCL_FUTURE",
    "MS_ASYNC",
    "MS_INVALIDATE",
    "MS_SYNC",
    "PROT_EXEC",
    "PROT_NONE",
    "PROT_READ",
    "PROT_WRITE",

    // Definitions from netdb.h
    "AI_ADDRCONFIG",
    "AI_ALL",
    "AI_CANONNAME",
    "AI_NUMERICHOST",
    "AI_NUMERICSERV",
    "AI_PASSIVE",
    "AI_V4MAPPED",
    "EAI_AGAIN",
    "EAI_BADFLAGS",
    "EAI_FAIL",
    "EAI_FAMILY",
    "EAI_MEMORY",
    "EAI_NODATA",
    "EAI_NONAME",
    "EAI_OVERFLOW",
    "EAI_SERVICE",
    "EAI_SOCKTYPE",
    "EAI_SYSTEM",
    "HOST_NOT_FOUND",
    "NI_NAMEREQD",
    "NI_NUMERICHOST",
    "NO_DATA",
    "NO_RECOVERY",
    "TRY_AGAIN",

    // Definitions from net/if.h
    "IFF_LOOPBACK",
    "IFF_MULTICAST",
    "IFF_POINTTOPOINT",
    "IFF_UP",
    "SIOCGIFADDR",
    "SIOCGIFBRDADDR",
    "SIOCGIFNETMASK",
    "SIOCGIFDSTADDR",

    // Definitions from netinet/in.h in6.h
    "IP_MULTICAST_IF",
    "IP_TOS",
    "IPPROTO_IP",
    "IPPROTO_IPV6",
    "IPPROTO_TCP",
    "IPV6_MULTICAST_HOPS",
    "IPV6_MULTICAST_IF",
    "IP_MULTICAST_LOOP",
    "IPV6_TCLASS",
    "MCAST_JOIN_GROUP",
    "MCAST_JOIN_GROUP",

    // Definitions from param.h
    "BSD",

    // Definitions from socket.h
    "AF_INET",
    "AF_INET6",
    "AF_UNIX",
    "AF_UNSPEC",
    "MSG_OOB",
    "MSG_PEEK",
    "SHUT_RD",
    "SHUT_RDWR",
    "SHUT_WR",
    "SOCK_DGRAM",
    "SOCK_STREAM",
    "SOL_SOCKET",
    "SO_BINDTODEVICE",
    "SO_BROADCAST",
    "SO_ERROR",
    "SO_KEEPALIVE",
    "SO_LINGER",
    "SO_OOBINLINE",
    "SO_REUSEADDR",
    "SO_RCVBUF",
    "SO_RCVTIMEO",
    "SO_SNDBUF",
    "TCP_NODELAY",

    // Definitions from stat.h
    "S_IFBLK",
    "S_IFCHR",
    "S_IFDIR",
    "S_IFIFO",
    "S_IFLNK",
    "S_IFMT",
    "S_IFREG",
    "S_IFSOCK",

    // Definitions from sys/event.h
    "EVFILT_READ",
    "EVFILT_WRITE",

    // Definitions from sys/poll.h
    "POLLERR",
    "POLLHUP",
    "POLLIN",
    "POLLOUT",

    // Definitions from sys/syslimits.h
    "ARG_MAX",
    "LINE_MAX",
    "MAX_INPUT",
    "NAME_MAX",
    "NZERO",
    "PATH_MAX",

    // Definitions from limits.h machine/limits.h
    "CHAR_BIT",
    "CHAR_MAX",
    "CHAR_MIN",
    "INT_MAX",
    "INT_MIN",
    "LLONG_MAX",
    "LLONG_MIN",
    "LONG_BIT",
    "LONG_MAX",
    "LONG_MIN",
    "MB_LEN_MAX",
    "OFF_MIN",
    "OFF_MAX",
    "PTHREAD_DESTRUCTOR_ITERATIONS",
    "PTHREAD_KEYS_MAX",
    "PTHREAD_STACK_MIN",
    "QUAD_MAX",
    "QUAD_MIN",
    "SCHAR_MAX",
    "SCHAR_MIN",
    "SHRT_MAX",
    "SHRT_MIN",
    "SIZE_T_MAX",
    "SSIZE_MAX",
    "UCHAR_MAX",
    "UINT_MAX",
    "ULONG_MAX",
    "UQUAD_MAX",
    "USHRT_MAX",
    "UULONG_MAX",
    "WORD_BIT",
    "NL_ARGMAX",
    "NL_LANGMAX",
    "NL_MSGMAX",
    "NL_NMAX",
    "NL_SETMAX",
    "NL_TEXTMAX",
    "IOV_MAX",

    // Definitions from mach/exception_types.h
    "EXC_BAD_ACCESS",
    "EXC_BAD_INSTRUCTION",
    "EXC_ARITHMETIC",
    "EXC_EMULATION",
    "EXC_SOFTWARE",
    "EXC_BREAKPOINT",
    "EXC_SYSCALL",
    "EXC_MACH_SYSCALL",
    "EXC_RPC_ALERT",
    "EXC_CRASH",
    "EXC_RESOURCE",
    "EXC_GUARD",
    "EXC_CORPSE_NOTIFY",
    "EXCEPTION_DEFAULT",
    "EXCEPTION_STATE",
    "EXCEPTION_STATE_IDENTITY",
    "MACH_EXCEPTION_CODES",
    "EXC_MASK_BAD_ACCESS",
    "EXC_MASK_BAD_INSTRUCTION",
    "EXC_MASK_ARITHMETIC",
    "EXC_MASK_EMULATION",
    "EXC_MASK_SOFTWARE",
    "EXC_MASK_BREAKPOINT",
    "EXC_MASK_SYSCALL",
    "EXC_MASK_MACH_SYSCALL",
    "EXC_MASK_RPC_ALERT",
    "EXC_MASK_CRASH",
    "EXC_MASK_RESOURCE",
    "EXC_MASK_GUARD",
    "EXC_MASK_CORPSE_NOTIFY",
    "EXC_MASK_ALL",
    "FIRST_EXCEPTION",
    "EXC_SOFT_SIGNAL",
    "EXC_MACF_MIN",
    "EXC_MACF_MAX",

    // Definitions from time.h
    "daylight",
    "getdate_err",
    "timezone",
    "tzname",

    // Definitions from types.h
    "S_IRGRP",
    "S_IROTH",
    "S_IRUSR",
    "S_IRWXG",
    "S_IRWXO",
    "S_IRWXU",
    "S_IWGRP",
    "S_IWOTH",
    "S_IWUSR",
    "S_IXGRP",
    "S_IXOTH",
    "S_IXUSR",

    // Definitions from unistd.h
    "F_OK",
    "R_OK",
    "STDERR_FILENO",
    "STDIN_FILENO",
    "STDOUT_FILENO",
    "W_OK",
    "X_OK",
    "_SC_PAGESIZE",
    "_SC_PAGE_SIZE",
    "optind",
    "opterr",
    "optopt",
    "optreset",

    // Definitions from mach/i386/vm_param.h
    "PAGE_SIZE",
    "PAGE_SHIFT",
    "PAGE_MASK",
    "I386_PGBYTES",

    // Cocoa definitions from ConditionalMacros.h
    "CFMSYSTEMCALLS",
    "CGLUESUPPORTED",
    "FUNCTION_PASCAL",
    "FUNCTION_DECLSPEC",
    "FUNCTION_WIN32CC",
    "GENERATING68881",
    "GENERATING68K",
    "GENERATINGCFM",
    "GENERATINGPOWERPC",
    "OLDROUTINELOCATIONS",
    "PRAGMA_ALIGN_SUPPORTED",
    "PRAGMA_ENUM_PACK",
    "PRAGMA_ENUM_ALWAYSINT",
    "PRAGMA_ENUM_OPTIONS",
    "PRAGMA_IMPORT",
    "PRAGMA_IMPORT_SUPPORTED",
    "PRAGMA_ONCE",
    "PRAGMA_STRUCT_ALIGN",
    "PRAGMA_STRUCT_PACK",
    "PRAGMA_STRUCT_PACKPUSH",
    "TARGET_API_MAC_CARBON",
    "TARGET_API_MAC_OS8",
    "TARGET_API_MAC_OSX",
    "TARGET_CARBON",
    "TYPE_BOOL",
    "TYPE_EXTENDED",
    "TYPE_LONGDOUBLE_IS_DOUBLE",
    "TYPE_LONGLONG",
    "UNIVERSAL_INTERFACES_VERSION",

    // Core Foundation definitions
    "BIG_ENDIAN",
    "BYTE_ORDER",
    "LITTLE_ENDIAN",
    "PDP_ENDIAN",
    "SEVERITY_ERROR",

    // CoreServices definitions
    "positiveInfinity",
    "negativeInfinity",

    // Common preprocessor definitions.
    "DEBUG",
    "NDEBUG",

    // Foundation methods with conflicting return types
    "scale",

    // Syntactic sugar for Objective-C block types
    "block_type"
  )

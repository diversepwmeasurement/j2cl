load(
    "//transpiler/javatests/com/google/j2cl/integration:integration_test.bzl",
    "integration_test",
)

package(
    licenses = ["notice"],  # Apache 2.0
)

# Creates targets
# blaze build :optimized_js
# blaze test :compiled_test
# blaze test :uncompiled_test
integration_test(
    name = "jsenum",
    srcs = glob([
        "*.java",
        "*.js",
    ]),
    enable_kt = False,
    # JsInterop features are not supported in WASM.
    enable_wasm = False,
    deps = [
        "//jre/java:javaemul_internal_annotations-j2cl",
        "//third_party:gwt-jsinterop-annotations-j2cl",
    ],
)
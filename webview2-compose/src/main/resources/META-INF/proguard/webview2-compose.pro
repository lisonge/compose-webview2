# Only annotated methods are reflective entry points; class names may change.
-keepclassmembers class * {
    @li.songe.compose.webview2.JavascriptInterface <methods>;
}

# DLL exports require only the surviving native entry point names.
-keepclasseswithmembernames class li.songe.compose.webview2.internal.NativeBridge {
    native <methods>;
}

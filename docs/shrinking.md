# Shrinking and obfuscation

The bridge scans public Java methods for runtime annotations. It no longer uses Kotlin reflection or automatically exports suspend methods.

## Library rules

The library JAR contains `META-INF/proguard/webview2-compose.pro`. Its source is [webview2-compose.pro](../webview2-compose/src/main/resources/META-INF/proguard/webview2-compose.pro). At the user's request it contains exactly two rules:

- Keep methods annotated with this library's JavascriptInterface; declaring class names can change.
- Keep the surviving NativeBridge native method names and class name for the statically exported JNI entry points.

There are no keepattributes, kotlin.Metadata or StabilityInferred rules in the library, and none were moved to application rules. The `class *` selector matches annotated methods in any application package; it does not keep all classes or their other members.

Runtime annotation scanning still requires the annotation to survive. The previously tested Compose Desktop default configuration supplies `-keepattributes RuntimeVisibleAnnotations,AnnotationDefault`. That is why the two library rules work in this tested setup. A custom application configuration that strips runtime annotations is not supported by these two rules alone. The verification checks annotations in the processed class and runs real calls; it does not infer success merely from the build passing.

## Native callback registration

Kotlin explicitly passes a `Map<String, Any>` to native with `invoke`, `navigated` and `close` protocol keys. Values implement JDK `Function<String[], String>`, `LongConsumer` and `Runnable`. Native validates the map and calls only these standard interface methods; it does not look up application class or method names. Global references own the callbacks until native teardown and keep the captured runtime alive.

`JavascriptBridgeRuntime` needs no keep rule and is checked to be renamed in the processed application. The remaining NativeBridge name rule supports statically exported JNI entry points. Annotated business methods need runtime annotations and stable JavaScript names, but no Kotlin metadata. JNA is no longer a dependency: the existing DLL reads the AWT window HWND through JAWT, releases the drawing surface immediately, and does not draw or change focus through it. JAWT is supplied by the desktop JDK/runtime (java.desktop).

## Compose Desktop integration

The sample and portable distribution do not enable minification. Ordinary usage requires only the library dependency. If an application enables ProGuard, it must explicitly load the embedded library rules; automatic discovery is not assumed.

An external application can extract the same file from its dependency JAR and configure:

```kotlin
compose.desktop {
    application {
        buildTypes.release.proguard {
            isEnabled.set(true)
            obfuscate.set(true)
            optimize.set(true)
            configurationFiles.from(project.file("webview2-compose.pro"))
        }
    }
}
```

If you copy the file, update it when upgrading the library. Application dependencies may require their own rules.

## Historical validation

Earlier minified builds passed synchronous JavaScript calls, explicit asynchronous messages, 21 background checks and close-frame verification with Kotlin 2.4.20, Compose 1.12.1, ProGuard 7.10.0 and JDK 21. These are historical results; the current sample no longer includes a minified build verification workflow. See [validation records](validation.md).

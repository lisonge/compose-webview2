# Development

Use JDK 21 and Visual Studio Build Tools 2022 or 2026 with the C++ desktop toolchain, Windows SDK and CMake component. Run commands from the repository root.

The supported environment is Windows x64 with the WebView2 Runtime installed. Gradle locates Visual Studio's bundled CMake automatically; no global Gradle installation is required.

```powershell
.\scripts\doctor.ps1
.\gradlew.bat :webview2-compose:check :webview2-sample:classes
.\gradlew.bat :webview2-compose:browserSmoke
.\gradlew.bat :webview2-compose:bridgeSmoke
```

`webview2-compose` is the Gradle library module; `webview2-sample` is the manual validation application. `webview2-native` contains the CMake sources and is not a separate Gradle module. Native binaries are built and packaged into the library automatically.

The Gradle group is `li.songe.webview2`; the planned library coordinate is `li.songe.webview2:webview2-compose:0.1.0`. Maven Local and Maven Central publishing are configured; see [publishing](publishing.md). Kotlin packages remain `li.songe.compose.webview2`.

`gradlew.bat :webview2-compose:buildNative` configures CMake using the installed Visual Studio 2022/2026 x64 generator, builds Release and installs the DLL under `webview2-compose/build/generated/nativeResources/native/windows-x64`. `processResources` depends on this task, so the DLL is included in the JAR. The loader extracts it to a unique temporary directory before calling System.load.

`gradlew.bat :webview2-compose:nativeSmoke` verifies the packaged JNI boundary without opening a window. `:webview2-compose:browserSmoke` uses a real hidden WebView2 to assert known captured pixels, DOM click handling and completed native teardown. Browser smoke requires hardware capture support and is opt-in, not part of hosted CI. Test sources currently contain this standalone integration main, not JUnit cases.

`gradlew.bat :webview2-sample:run` opens the interactive browser/overlay demo. Do not run this unattended on the user's working desktop; use `:webview2-sample:run --args="--test"` instead.

Native compiler outputs live in `webview2-native/build`; all generated files are ignored. `gradlew.bat clean` cleans Gradle modules; remove the explicitly identified `webview2-native/build` directory separately when a fresh CMake configuration is needed.

The WebView2 SDK is pinned in `webview2-native/cmake/WebView2Sdk.cmake`, fetched and SHA-256 verified by CMake. It is separate from the installed WebView2 Runtime. The loader is statically linked. Sample browser data stays in `webview2-sample/build/demo-profile`; browser smoke data stays in `webview2-compose/build/browser-smoke-profile`. The project uses Apache-2.0; release signing is configured in CI.

The first native build downloads WebView2 SDK 1.0.3650.58. For unattended testing, use `:webview2-sample:run --args="--test"` and `scripts/verify-background.ps1`. This starts an off-screen, non-activating sample with a loopback Ktor API and does not inject system keyboard or mouse input. See [background testing](background-testing.md).

The library enables Kotlin explicit API mode: exported declarations must state their visibility and inferred property types must be made explicit. The sample does not enable this mode.

`check` compiles and runs ordinary tests without invoking graphics diagnostics. Run `nativeSmoke`, `browserSmoke`, and `bridgeSmoke` explicitly on a suitable Windows machine. Native compilation still requires the Windows build toolchain.

`buildNative` tracks the selected JDK installation path, its `include` directory, `release` metadata and `lib/jawt.lib`, in addition to native sources and the build script. Changes to those inputs invalidate Gradle's up-to-date result.

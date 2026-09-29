# Architecture

Implemented pipeline: WebView2 CompositionController → Windows.UI.Composition visual → Graphics Capture → D3D11 staging texture → latest BGRA frame → JNI → immutable Skia-backed ImageBitmap → Compose Canvas.

`webview2-native/src/webview_host.cpp` owns a registry of opaque session IDs and a dedicated STA/message-pump thread per session. Environment/controller creation is asynchronous. Windows Composition visuals are not attached to a desktop composition target. The real AWT HWND is supplied to WebView2 for hosting and native focus; the separate native smoke test uses a hidden host. No visible browser HWND is inserted above Compose.

Commands are queued onto the STA thread. Async callbacks hold weak references. A stopping flag rejects new commands while native teardown releases capture, controller and composition resources. Registry removal follows teardown; per-instance completion is available independently of global diagnostics. A 30-second initialization timeout reports failure.

The capture frame pool has two buffers and the native host retains only the newest CPU frame. JNI returns a frame only when its sequence changes. Rows are copied using D3D's actual row pitch. On size changes, the capture item/session is recreated to match the visual dimensions. Kotlin validates dimensions before creating immutable frame snapshots, so draw recordings do not reference overwritten pixels. This implementation allocates and copies per frame; GPU interop and buffer reuse remain performance work.

Compose performs the usual hit testing before forwarding mouse/wheel events to WebView2. The native host does not subclass the parent window or intercept its mouse events. Browser local coordinates come from Compose pointer events; rasterization scale follows Compose density. `MoveFocus` and `MoveFocusRequested` bridge native keyboard focus and Tab traversal in interactive mode, but that path has not passed real IME/focus acceptance. Test settings disable all native focus requests.

Ktor HTTP control, screenshot composition and non-activating window guards live only in `webview2-sample`; they are not runtime requirements of the library. Test screenshots come from Skia recordings, including ordinary owned Compose windows, not screen capture of the user's desktop.

Application shutdown must keep the parent HWND alive until native teardown finishes. The sample sets `WebView(running = false)` while keeping the composable and its last captured image in the scene. After that state’s awaitClosed() completes, it calls exitApplication. This avoids blanking the browser area before the OS close animation. No artificial delay is added to normal shutdown. Setting running back to true creates a new browser session; it does not resume the old DOM. A multi-window application waits for the WebViews owned by the parent being closed. The UI thread must not synchronously join the native STA, which can need window messages to complete cleanup.


## Instance completion and frame metadata

`closeAndWait` runs on a Kotlin IO worker and waits for the particular native host's completion future. STA teardown releases the controller, capture/composition resources and dispatcher before signaling. Cleanup steps are isolated so one failure does not skip the remainder. `awaitClosed` holds a session-specific deferred and does not depend on process-wide host counts; state completion is posted to the AWT event thread. Closing errors are returned before discarding the native host. A waiter timeout does not interrupt the native cleanup.

The private frame wire header is 32 bytes: width/height (int32), sequence/revision (int64), scale (float64), followed by BGRA bytes. Metadata and pixels are copied under the same mutex. A requested viewport revision is explicit, not inferred from frame size. After resize, the browser confirms `innerWidth/innerHeight/devicePixelRatio`; capture is restarted to obtain a newer snapshot even for a static page. This is a viewport acknowledgement, not proof that every page animation/font/image has finished painting.

The renderer passes the pixel range to `Data.makeFromBytes` and then `Image.makeRaster`, avoiding the previous JVM `copyOfRange` pixel array. Data still owns an immutable native copy; this is not zero-copy or GPU interop. See [performance baseline](performance.md).

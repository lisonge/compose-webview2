package li.songe.compose.webview2.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.LocalAwtWindow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import li.songe.compose.webview2.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

@OptIn(ExperimentalComposeUiApi::class)
private fun Modifier.record(model: DemoModel, name: String) = composed {
    val owner = LocalAwtWindow.current
    onGloballyPositioned {
        val base = layer(model.window)?.canvas
        val current = owner?.let(::layer)?.canvas
        val offset = if (base != null && current != null && base.isShowing && current.isShowing) {
            val a = base.locationOnScreen
            val b = current.locationOnScreen
            val scale = model.window.graphicsConfiguration.defaultTransform.scaleX.toFloat()
            Offset((b.x - a.x) * scale, (b.y - a.y) * scale)
        } else Offset.Zero
        model.bounds[name] = it.boundsInWindow().translate(offset)
    }
}

fun main(args: Array<String>) {
    val runtimeAvailable = WebView2Runtime.getAvailableVersion() != null
    val testing = "--test" in args
    val guard = if (testing) BackgroundWindows.install() else null
    val network = SampleNetworkFixture()
    val api = SampleJavascriptApi()
    val profile = if (!testing && System.getProperty("jpackage.app-path") != null)
        File(System.getProperty("jpackage.app-path")).parentFile.resolve("data/profile")
    else File("build/demo-profile")
    try {
        application(exitProcessOnExit = false) {
            val model = remember { DemoModel() }
            val state = rememberWebViewState(network.pageOrigin)
            val bindings = rememberWebViewBindings {
                allowOrigin(network.pageOrigin)
                addJavascriptInterface(api, "NativeApp")
                addWebMessageListener("NativeMessages", api::onMessage)
                addDocumentStartScript(checkNotNull(SampleJavascriptApi::class.java.getResource("/pages/bridge.js")).readText())
                addJavascriptVariable("appPlatform", kotlinx.serialization.json.JsonPrimitive("Windows"))
                addJavascriptVariable("apiOrigin", kotlinx.serialization.json.JsonPrimitive(network.apiOrigin))
            }
            model.web = state
            model.quit = { model.closing = true }
            LaunchedEffect(model.closing, model.holdCloseForTest) {
                if (!model.closing) return@LaunchedEffect
                model.shutdownReady = false
                model.shutdownError = null
                // Keep the complete scene, including the last browser frame, in
                // place while the STA releases resources. Do not blank the area.
                val closed = withTimeoutOrNull(5000) {
                    state.awaitClosed()
                    true
                } == true
                if (closed) {
                    println("[compose-webview2] Native teardown complete before window disposal; instance closed")
                    if (testing && model.holdCloseForTest) {
                        model.shutdownReady = true
                    } else exitApplication()
                } else {
                    model.shutdownError = "Native shutdown is still pending. The window was kept open; retry closing shortly."
                    model.closing = false
                    System.err.println("[compose-webview2] Native teardown timed out; keeping parent window alive")
                }
            }
            val windowState = rememberWindowState(width = 1100.dp, height = 760.dp,
                position = if (testing) WindowPosition.Absolute((-10000).dp, (-10000).dp) else WindowPosition.PlatformDefault)
            Window(onCloseRequest = model.quit, title = "Compose WebView2 — integration demo", state = windowState, focusable = !testing) {
                model.window = window
                DisposableEffect(Unit) {
                    val server = if (testing) startDebugServer(model) else null
                    onDispose { server?.close() }
                }
                MaterialTheme {
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("WebView2 inside Compose", style = MaterialTheme.typography.headlineMedium)
                            model.shutdownError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(onClick = { model.popup = !model.popup }, Modifier.record(model, "openPopup")) { Text("Popup") }
                                Button(onClick = { model.dialog = true }, Modifier.record(model, "openDialog")) { Text("Dialog") }
                                Box {
                                    Button(onClick = { model.menu = true }, Modifier.record(model, "openMenu")) { Text("DropdownMenu") }
                                    DropdownMenu(expanded = model.menu, onDismissRequest = { model.menu = false }) {
                                        DropdownMenuItem(text = { Text("Ordinary Compose menu") }, onClick = { model.popupClicks++; model.menu = false }, modifier = Modifier.record(model, "menuItem"))
                                    }
                                }
                                Button(onClick = { model.mounted = !model.mounted }) { Text(if (model.mounted) "Dispose" else "Recreate") }
                            }
                            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                Column(Modifier.width(240.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text("Independent Compose region", style = MaterialTheme.typography.titleMedium)
                                    OutlinedTextField(model.composeText, { model.composeText = it }, Modifier.record(model, "composeInput"), label = { Text("Compose input") })
                                    Text("Status: ${state.status}")
                                    Text("Captured: ${state.frameWidth} × ${state.frameHeight}")
                                    Text("Overlay clicks: ${model.overlayClicks}")
                                    Text("Popup clicks: ${model.popupClicks}")
                                    Text("Dialog clicks: ${model.dialogClicks}")
                                    Text("Opacity")
                                    Slider(model.opacity, { model.opacity = it })
                                    Text(if (testing) "Background HTTP mode\nNative focus disabled" else "Click the page to test native keyboard / IME")
                                }
                                Box(Modifier.weight(1f).fillMaxHeight().background(Color(0xFFE2E8F0), RoundedCornerShape(20.dp)).record(model, "webArea")) {
                                    if (!runtimeAvailable) Text(
                                        "未检测到已安装的WebView2",
                                        modifier = Modifier.align(Alignment.Center),
                                    )
                                    else if (model.mounted) WebView(state,
                                        Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)).graphicsLayer { alpha = model.opacity },
                                        WebViewSettings(profile.absolutePath, requestNativeFocus = !testing, preferredColorScheme = model.colorScheme), running = !model.closing, bindings = bindings)
                                    Button(onClick = { model.overlayClicks++ },
                                        Modifier.align(Alignment.TopEnd).padding(18.dp).record(model, "overlay")) { Text("Compose overlay") }
                                    if (model.popup) Popup(alignment = Alignment.Center, onDismissRequest = { model.popup = false }, properties = PopupProperties(focusable = true)) {
                                        Surface(shadowElevation = 12.dp, shape = RoundedCornerShape(16.dp), color = Color(0xFFFFE4B5)) {
                                            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                                Text("Standard Compose Popup")
                                                Button(onClick = { model.popupClicks++ }, Modifier.record(model, "popupAction")) { Text("Popup click ${model.popupClicks}") }
                                                Button(onClick = { model.popup = false }, Modifier.record(model, "popupClose")) { Text("Close popup") }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if (model.dialog) AlertDialog(onDismissRequest = { model.dialog = false },
                            title = { Text("Standard Compose Dialog") }, text = { Text("Browser frames stay below this dialog.") },
                            confirmButton = { Button(onClick = { model.dialogClicks++; model.dialog = false }, Modifier.record(model, "dialogConfirm")) { Text("Confirm") } })
                    }
                }
            }
        }
    } finally { api.close(); network.close(); guard?.close() }
}

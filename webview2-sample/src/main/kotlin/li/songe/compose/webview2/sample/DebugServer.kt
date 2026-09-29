package li.songe.compose.webview2.sample

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Rect
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import li.songe.compose.webview2.WebViewColorScheme
import li.songe.compose.webview2.WebViewState
import li.songe.compose.webview2.WebViewDiagnostics
import org.jetbrains.skia.Image
import org.jetbrains.skiko.SkiaLayer
import java.awt.Component
import java.awt.Container
import java.awt.Window
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import javax.imageio.ImageIO

class DemoModel {
    lateinit var window: Window
    lateinit var web: WebViewState
    var popup by mutableStateOf(false)
    var dialog by mutableStateOf(false)
    var menu by mutableStateOf(false)
    var mounted by mutableStateOf(true)
    var closing by mutableStateOf(false)
    var shutdownError by mutableStateOf<String?>(null)
    var holdCloseForTest by mutableStateOf(false)
    var shutdownReady by mutableStateOf(false)
    var overlayClicks by mutableIntStateOf(0)
    var popupClicks by mutableIntStateOf(0)
    var dialogClicks by mutableIntStateOf(0)
    var composeText by mutableStateOf("")
    var colorScheme by mutableStateOf(WebViewColorScheme.Auto)
    var opacity by mutableFloatStateOf(1f)
    val bounds = mutableMapOf<String, Rect>()
    var quit: () -> Unit = {}
}

internal fun layer(component: Component): SkiaLayer? = when (component) {
    is SkiaLayer -> component
    is Container -> component.components.firstNotNullOfOrNull(::layer)
    else -> null
}
private fun windows(main: Window): List<Window> = listOf(main) + main.ownedWindows.filter { it.isShowing }.flatMap(::windows)

private fun snapshot(main: Window): ByteArray {
    val base = requireNotNull(layer(main))
    val scale = main.graphicsConfiguration.defaultTransform.scaleX
    val image = BufferedImage((base.canvas.width * scale).toInt(), (base.canvas.height * scale).toInt(), BufferedImage.TYPE_INT_ARGB)
    val origin = base.canvas.locationOnScreen
    val graphics = image.createGraphics()
    try {
        for (window in windows(main)) {
            val current = layer(window) ?: continue
            current.screenshot()?.use { bitmap ->
                Image.makeFromBitmap(bitmap).use { frame ->
                    frame.encodeToData()?.use { data ->
                        val decoded = ImageIO.read(ByteArrayInputStream(data.bytes))
                        val p = current.canvas.locationOnScreen
                        graphics.drawImage(decoded, ((p.x - origin.x) * scale).toInt(), ((p.y - origin.y) * scale).toInt(), null)
                    }
                }
            }
        }
    } finally { graphics.dispose() }
    return ByteArrayOutputStream().use { ImageIO.write(image, "png", it); it.toByteArray() }
}

private fun click(model: DemoModel, x: Int, y: Int, wheel: Int?) {
    val base = requireNotNull(layer(model.window)).canvas
    val scale = model.window.graphicsConfiguration.defaultTransform.scaleX
    val baseOrigin = base.locationOnScreen
    val screenX = baseOrigin.x + (x / scale).toInt()
    val screenY = baseOrigin.y + (y / scale).toInt()
    val target = windows(model.window).asReversed().mapNotNull(::layer).firstOrNull {
        java.awt.Rectangle(it.canvas.locationOnScreen, it.canvas.size).contains(screenX, screenY)
    } ?: error("No sample window at point")
    val canvas = target.canvas
    val p = canvas.locationOnScreen
    val cx = screenX - p.x
    val cy = screenY - p.y
    fun event(kind: Int, button: Int = MouseEvent.NOBUTTON, mask: Int = 0) = MouseEvent(canvas, kind,
        System.currentTimeMillis(), mask, cx, cy, screenX, screenY, if (button == 0) 0 else 1, false, button)
    canvas.dispatchEvent(event(MouseEvent.MOUSE_MOVED))
    if (wheel != null) {
        val receiver = generateSequence(canvas as Component) { it.parent }.firstOrNull { it.mouseWheelListeners.isNotEmpty() }
            ?: error("No wheel receiver")
        val point = javax.swing.SwingUtilities.convertPoint(canvas, cx, cy, receiver)
        receiver.dispatchEvent(MouseWheelEvent(receiver, MouseEvent.MOUSE_WHEEL, System.currentTimeMillis(), 0,
            point.x, point.y, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, wheel))
    } else {
        canvas.dispatchEvent(event(MouseEvent.MOUSE_PRESSED, MouseEvent.BUTTON1, InputEvent.BUTTON1_DOWN_MASK))
        canvas.dispatchEvent(event(MouseEvent.MOUSE_RELEASED, MouseEvent.BUTTON1))
        canvas.dispatchEvent(event(MouseEvent.MOUSE_CLICKED, MouseEvent.BUTTON1))
    }
}

fun startDebugServer(model: DemoModel): AutoCloseable {
    val token = UUID.randomUUID().toString()
    val port = System.getenv("WEBVIEW2_TEST_PORT")?.toInt() ?: 18765
    File("build").mkdirs()
    File("build/http-token").writeText(token)
    val server = embeddedServer(CIO, host = "127.0.0.1", port = port) {
        install(createApplicationPlugin("TestToken") {
            onCall { call -> if (call.request.headers["X-Test-Token"] != token) call.respond(HttpStatusCode.Unauthorized) }
        })
        routing {
            get("/state") {
                val result = withContext(Dispatchers.Swing) {
                    buildJsonObject {
                        put("url", model.web.url); put("title", model.web.title)
                        put("isLoading", model.web.isLoading); put("canGoBack", model.web.canGoBack); put("canGoForward", model.web.canGoForward)
                        put("status", model.web.status); put("frames", model.web.frameCount)
                        put("lifecycle", model.web.lifecycle.name)
                        put("errorStage", model.web.error?.stage?.name)
                        put("viewportReady", model.web.isViewportReady)
                        put("viewportRevision", model.web.requestedViewport?.revision)
                        put("frameRevision", model.web.frameViewport?.revision)
                        put("frameScale", model.web.frameViewport?.scale)
                        put("allocatedBytes", (java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean).totalThreadAllocatedBytes)
                        put("width", model.web.frameWidth); put("height", model.web.frameHeight)
                        put("overlayClicks", model.overlayClicks); put("popupClicks", model.popupClicks)
                        put("dialogClicks", model.dialogClicks); put("popup", model.popup); put("dialog", model.dialog)
                        put("menu", model.menu); put("mounted", model.mounted); put("activations", BackgroundWindows.activations)
                        put("closing", model.closing)
                        put("shutdownReady", model.shutdownReady)
                        put("composeText", model.composeText)
                        put("activeNativeHosts", WebViewDiagnostics.activeNativeHosts)
                        put("heapBytes", Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
                        put("processCpuNanos", (java.lang.management.ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean).processCpuTime)
                        putJsonObject("bounds") { model.bounds.forEach { (name, rect) ->
                            putJsonObject(name) { put("x", rect.left); put("y", rect.top); put("width", rect.width); put("height", rect.height) }
                        } }
                    }.toString()
                }
                call.respondText(result, ContentType.Application.Json)
            }
            get("/screenshot") { call.respondBytes(withContext(Dispatchers.Swing) { snapshot(model.window) }, ContentType.Image.PNG) }
            post("/pointer") {
                val values = Json.parseToJsonElement(call.receiveText()).jsonObject
                withContext(Dispatchers.Swing) { click(model, values.getValue("x").jsonPrimitive.int, values.getValue("y").jsonPrimitive.int, values["wheel"]?.jsonPrimitive?.int) }
                call.respondText("ok")
            }
            post("/control") {
                val values = Json.parseToJsonElement(call.receiveText()).jsonObject
                withContext(Dispatchers.Swing) {
                    values["popup"]?.let { model.popup = it.jsonPrimitive.boolean }
                    values["dialog"]?.let { model.dialog = it.jsonPrimitive.boolean }
                    values["menu"]?.let { model.menu = it.jsonPrimitive.boolean }
                    values["mounted"]?.let { model.mounted = it.jsonPrimitive.boolean }
                    values["colorScheme"]?.let { model.colorScheme = WebViewColorScheme.valueOf(it.jsonPrimitive.content) }
                    values["opacity"]?.let { model.opacity = it.jsonPrimitive.float.coerceIn(0f, 1f) }
                    values["width"]?.let { model.window.setSize(it.jsonPrimitive.int.coerceIn(640, 1600), model.window.height) }
                    values["url"]?.let { model.web.loadUrl(it.jsonPrimitive.content) }
                    values["goBack"]?.let { model.web.goBack() }
                    values["goForward"]?.let { model.web.goForward() }
                    values["stopLoading"]?.let { model.web.stopLoading() }
                    values["reload"]?.let { model.web.reload() }
                }
                call.respondText("ok")
            }
            post("/text") {
                val value = call.receiveText()
                withContext(Dispatchers.Swing) {
                    val canvas = requireNotNull(layer(model.window)).canvas
                    value.forEach { char ->
                        val event = KeyEvent(canvas, KeyEvent.KEY_TYPED, System.currentTimeMillis(), 0, KeyEvent.VK_UNDEFINED, char)
                        canvas.keyListeners.forEach { it.keyTyped(event) }
                    }
                }
                call.respondText("ok")
            }
            post("/evaluate") {
                val script = call.receiveText()
                val result = withContext(Dispatchers.Swing) { model.web.evaluateJavaScript(script) }
                call.respondText(result, ContentType.Application.Json)
            }
            post("/quit") {
                val hold = call.request.queryParameters["hold"] == "true"
                call.respondText("closing")
                withContext(Dispatchers.Swing) {
                    model.holdCloseForTest = hold
                    model.quit()
                }
            }
        }
    }.start(wait = false)
    println("Background test HTTP server: http://127.0.0.1:$port (token in webview2-sample/build/http-token)")
    return AutoCloseable { server.stop(100, 1000) }
}

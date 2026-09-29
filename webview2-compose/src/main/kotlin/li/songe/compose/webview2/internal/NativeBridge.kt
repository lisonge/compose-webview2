package li.songe.compose.webview2.internal

internal object NativeBridge {
    init { NativeLoader.load() }
    external fun windowHandle(window: java.awt.Window): Long
    external fun availableBrowserVersion(): String?
    external fun diagnostics(): String
    external fun activeHosts(): Int
    external fun takeFocusMove(id: Long): Int
    external fun create(parent: Long, profile: String, colorScheme: Int = 0): Long
    external fun createBound(parent: Long, profile: String, callbacks: Map<String, Any>, script: String, colorScheme: Int = 0): Long
    external fun bridgeReply(id: Long, generation: Long, json: String)
    external fun colorScheme(id: Long, value: Int)
    external fun configure(id: Long, userAgent: String?, javaScript: Boolean, zoom: Double,
        userZoom: Boolean, devTools: Boolean, contextMenu: Boolean, statusBar: Boolean)
    fun configure(id: Long, settings: li.songe.compose.webview2.WebViewSettings) = configure(
        id, settings.userAgent, settings.javaScriptEnabled, settings.zoomFactor,
        settings.userZoomEnabled, settings.devToolsEnabled, settings.contextMenuEnabled, settings.statusBarEnabled)
    external fun setClient(id: Long, callback: java.util.function.Function<Array<String>, String>)
    external fun pageState(id: Long): Array<String>
    external fun navigationAction(id: Long, action: Int)
    external fun close(id: Long)
    external fun isClosed(id: Long): Boolean
    external fun errorState(id: Long): Array<String>
    external fun closeAndWait(id: Long): Array<String>
    external fun resize(id: Long, x: Int, y: Int, width: Int, height: Int, scale: Double, revision: Long = -1)
    external fun navigate(id: Long, value: String, html: Boolean)
    external fun mouse(id: Long, message: Int, keys: Int, data: Int, x: Int, y: Int)
    external fun focus(id: Long, enabled: Boolean)
    external fun status(id: Long): String
    external fun frame(id: Long, after: Long): ByteArray?
    external fun script(id: Long, value: String)
    external fun scriptResult(id: Long): String
}

internal fun main() {
    val result = NativeBridge.diagnostics()
    check(result.contains("JNI OK")) { result }
    println(result)
    val hosts = NativeBridge.activeHosts()
    val version = li.songe.compose.webview2.WebView2Runtime.getAvailableVersion()
    check(version == null || version.isNotBlank())
    check(NativeBridge.activeHosts() == hosts) { "Runtime query created a browser" }
    println("Available WebView2 browser: ${version ?: "not found"}")
}

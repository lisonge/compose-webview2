package li.songe.compose.webview2.internal

import li.songe.compose.webview2.*
import java.util.function.Function

internal class WebViewClientRuntime(client: WebViewClient?) {
    @Volatile var client: WebViewClient? = client
    @Volatile private var closed = false
    val callback = Function<Array<String>, String> { args ->
        if (closed) "ignore" else when (args[0]) {
            "key" -> if (this.client?.onAcceleratorKey?.invoke(WebViewKeyEvent(
                args[1].toInt(), args[2] == "true", args[3] == "true", args[4] == "true",
                args[5] == "true", args[6] == "true", args[7] == "true")) == true) "handled" else "ignore"
            "navigation" -> if (this.client?.onNavigationRequest?.invoke(
                WebViewNavigationRequest(args[1], args[2] == "true", args[3] == "true")) != false) "allow" else "ignore"
            "newWindow" -> if (this.client?.onNewWindowRequest?.invoke(
                WebViewNewWindowRequest(args[1], args[2] == "true")) == WebViewNewWindowAction.OpenInCurrentWebView) "current" else "ignore"
            else -> "ignore"
        }
    }
    fun close() { closed = true; client = null }
}

package li.songe.compose.webview2

/** Main-frame navigation, including redirects. Callbacks run synchronously on the browser STA. */
public data class WebViewNavigationRequest(val url: String, val isUserInitiated: Boolean, val isRedirect: Boolean)
public data class WebViewNewWindowRequest(val url: String, val isUserInitiated: Boolean)
public enum class WebViewNewWindowAction { Ignore, OpenInCurrentWebView }

/** Return promptly; never block waiting for Compose UI or JavaScript. Dispatch external work yourself. */
public class WebViewClient(
    public val onNavigationRequest: (WebViewNavigationRequest) -> Boolean = { true },
    public val onNewWindowRequest: (WebViewNewWindowRequest) -> WebViewNewWindowAction = { WebViewNewWindowAction.Ignore },
    public val onAcceleratorKey: (WebViewKeyEvent) -> Boolean = { false },
)

/** Native Windows accelerator key; not a text-input or IME event. */
public data class WebViewKeyEvent(
    val virtualKey: Int,
    val isKeyDown: Boolean,
    val isSystemKey: Boolean,
    val isRepeat: Boolean,
    val control: Boolean,
    val alt: Boolean,
    val shift: Boolean,
)

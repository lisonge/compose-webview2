package li.songe.compose.webview2

import java.nio.file.Path

/** Preferred browser appearance; shared by WebViews using the same profile. */
public enum class WebViewColorScheme { Auto, Light, Dark }

public data class WebViewSettings(
    val userDataDirectory: String = Path.of(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"),
        "compose-webview2", "profile").toString(),
    /** Disable in unattended tests. Mouse interaction still works, but native keyboard/IME will not. */
    val requestNativeFocus: Boolean = true,
    /** Auto follows the OS. Updates do not reload the page; the last profile update wins. */
    val preferredColorScheme: WebViewColorScheme = WebViewColorScheme.Auto,
    /** Null restores the browser UA. Applied to subsequent requests; no reload is forced. */
    val userAgent: String? = null,
    /** Page scripts, effective on the next navigation; does not disable host script execution. */
    val javaScriptEnabled: Boolean = true,
    /** Whole-page scale, applied immediately. */
    val zoomFactor: Double = 1.0,
    /** Browser settings below take effect on subsequent navigation. */
    val userZoomEnabled: Boolean = false,
    val devToolsEnabled: Boolean = false,
    val contextMenuEnabled: Boolean = false,
    val statusBarEnabled: Boolean = false,
) {
    init {
        require(zoomFactor.isFinite() && zoomFactor > 0) { "zoomFactor must be finite and positive" }
        require(userAgent == null || (userAgent.isNotBlank() && userAgent.none { it == '\r' || it == '\n' || it == '\u0000' })) {
            "userAgent must be nonblank and contain no CR, LF or NUL"
        }
    }
}

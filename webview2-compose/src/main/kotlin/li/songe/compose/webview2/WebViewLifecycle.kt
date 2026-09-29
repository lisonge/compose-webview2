package li.songe.compose.webview2

public enum class WebViewLifecycle { Detached, Initializing, Active, Closing, Closed }
public enum class WebViewErrorStage { Initialization, Navigation, Rendering, BrowserProcess, Callback, Operation, Closing }
public data class WebViewError(
    val stage: WebViewErrorStage,
    val message: String,
    val hresult: Long? = null,
    val url: String? = null,
    val navigationErrorCode: Int? = null,
    val processFailureKind: Int? = null,
)

package li.songe.compose.webview2

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import java.awt.EventQueue
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import li.songe.compose.webview2.internal.NativeBridge

@Stable
public class WebViewState internal constructor(initialContent: String, initialHtml: Boolean) {
    public var lifecycle: WebViewLifecycle by mutableStateOf(WebViewLifecycle.Detached)
        internal set
    public var error: WebViewError? by mutableStateOf(null)
        internal set
    private val closingIds = mutableSetOf<Long>()
    private var closure: CompletableDeferred<Unit>? = null
    internal fun attached() {
        closure = CompletableDeferred()
        lifecycle = WebViewLifecycle.Initializing
        error = null
    }
    /** Waits for the session present at invocation (or the last session if detached).
     * Safe immediately after setting running=false, before recomposition. Does not initiate closing.
     * Cancellation stops only this waiter. New sessions do not extend this wait.
     */
    public suspend fun awaitClosed() { closure?.await() }
    internal fun closing(id: Long) {
        val completion = closure ?: return
        if (!closingIds.add(id)) return
        lifecycle = WebViewLifecycle.Closing
        status = "Closing"
        cleanupScope.launch {
            val result = runCatching { NativeBridge.closeAndWait(id) }
            EventQueue.invokeLater {
                closingIds.remove(id)
                if (closure === completion) {
                    lifecycle = if (result.isSuccess) WebViewLifecycle.Closed else WebViewLifecycle.Closing
                    status = if (result.isSuccess) "Closed" else "Error: native close wait failed"
                    result.fold(
                        onSuccess = { if (it.isNotEmpty()) updateError(it) },
                        onFailure = { error = WebViewError(WebViewErrorStage.Closing, it.message ?: it.toString()) },
                    )
                }
                result.fold(onSuccess = { completion.complete(Unit) }, onFailure = { completion.completeExceptionally(it) })
            }
        }
    }
    internal fun initializationFailed(message: String, complete: Boolean = true) {
        error = WebViewError(WebViewErrorStage.Initialization, message)
        status = "Error: $message"
        lifecycle = WebViewLifecycle.Closed
        if (complete) closure?.complete(Unit)
    }
    internal fun updateError(values: Array<String>) {
        error = if (values.isEmpty()) null else WebViewError(
            WebViewErrorStage.valueOf(values[0]), values[1], values[2].toLongOrNull(),
            values[3].ifEmpty { null }, values[4].toIntOrNull(), values[5].toIntOrNull())
    }
    public var requestedViewport: WebViewViewport? by mutableStateOf(null)
        internal set
    public var frameViewport: WebViewViewport? by mutableStateOf(null)
        internal set
    /** A newly captured frame matches the confirmed browser viewport; not a page-load guarantee. */
    public val isViewportReady: Boolean get() = session != 0L && requestedViewport != null && requestedViewport == frameViewport
    public var status: String by mutableStateOf("Not attached")
        internal set
    public var frameCount: Long by mutableLongStateOf(0)
        internal set
    public var frameWidth: Int by mutableIntStateOf(0)
        internal set
    public var frameHeight: Int by mutableIntStateOf(0)
        internal set
    public var url: String by mutableStateOf("")
        internal set
    public var title: String by mutableStateOf("")
        internal set
    public var isLoading: Boolean by mutableStateOf(false)
        internal set
    public var canGoBack: Boolean by mutableStateOf(false)
        internal set
    public var canGoForward: Boolean by mutableStateOf(false)
        internal set
    public fun goBack() { if (session != 0L) NativeBridge.navigationAction(session, 0) }
    public fun goForward() { if (session != 0L) NativeBridge.navigationAction(session, 1) }
    public fun stopLoading() { if (session != 0L) NativeBridge.navigationAction(session, 2) }
    internal fun updatePage(values: Array<String>) {
        if (lifecycle == WebViewLifecycle.Initializing && values.getOrNull(5) == "true") lifecycle = WebViewLifecycle.Active
        url = values[0]; title = values[1]; isLoading = values[2] == "true"
        canGoBack = values[3] == "true"; canGoForward = values[4] == "true"
    }
    internal var content by mutableStateOf(initialContent)
    internal var html by mutableStateOf(initialHtml)
    internal var navigationRevision by mutableIntStateOf(0)
    internal var session by mutableLongStateOf(0)
    private val scriptLock = Mutex()

    public fun loadUrl(url: String) { html = false; content = url; navigationRevision++ }
    public fun loadHtml(value: String) { html = true; content = value; navigationRevision++ }
    public fun reload() { if (session != 0L) NativeBridge.navigationAction(session, 3) else navigationRevision++ }

    /** Returns WebView2's JSON-encoded result. Calls are serialized and time-limited. */
    public suspend fun evaluateJavaScript(script: String): String = scriptLock.withLock {
        val id = session
        check(id != 0L) { "WebView is not attached" }
        NativeBridge.script(id, script)
        withTimeout(5000) {
            var result = ""
            while (result.isEmpty()) {
                check(session == id) { "WebView was disposed" }
                delay(10)
                result = NativeBridge.scriptResult(id)
            }
            result
        }
    }
}

@Composable
public fun rememberWebViewState(url: String = "about:blank"): WebViewState = remember { WebViewState(url, false) }

@Composable
public fun rememberWebViewHtmlState(html: String): WebViewState = remember { WebViewState(html, true) }

private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

public data class WebViewViewport(val width: Int, val height: Int, val scale: Double, val revision: Long)

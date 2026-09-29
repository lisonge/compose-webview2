package li.songe.compose.webview2.internal

import li.songe.compose.webview2.*
import kotlinx.coroutines.*
import java.awt.EventQueue
import java.nio.file.Path

private fun edt(action: () -> Unit) = EventQueue.invokeAndWait(action)
private suspend fun until(label: String, condition: () -> Boolean) {
    withTimeout(30_000) { while (!condition()) delay(20) }
    println("PASS: $label")
}

fun main() = runBlocking {
    val profile = Path.of("build", "lifecycle-smoke-profile").toAbsolutePath().toString()
    val first = NativeBridge.create(0, profile)
    val other = NativeBridge.create(0, profile)
    val state = WebViewState("about:blank", false)
    try {
        until("two ready instances") { NativeBridge.status(first) == "Ready" && NativeBridge.status(other) == "Ready" }
        edt { state.attached(); state.session = first }
        val canceled = async(start = CoroutineStart.UNDISPATCHED) { state.awaitClosed() }
        canceled.cancelAndJoin()
        val waiter = async(start = CoroutineStart.UNDISPATCHED) { state.awaitClosed() }
        delay(100)
        check(!waiter.isCompleted) { "awaitClosed returned before disposal" }
        edt { state.closing(first); state.closing(first); state.session = 0 }
        withTimeout(5_000) { waiter.await() }
        check(state.lifecycle == WebViewLifecycle.Closed)
        check(!NativeBridge.isClosed(other)) { "Closing one instance affected the other" }
        check(NativeBridge.activeHosts() == 1)
        println("PASS: per-instance close, early waiter, repeated close, canceled waiter")

        edt { state.attached(); state.session = other }
        val oldWaiter = async(start = CoroutineStart.UNDISPATCHED) { state.awaitClosed() }
        edt { state.closing(other); state.session = 0; state.attached() }
        withTimeout(5_000) { oldWaiter.await() }
        check(state.lifecycle == WebViewLifecycle.Initializing)
        val newWaiter = async(start = CoroutineStart.UNDISPATCHED) { state.awaitClosed() }
        check(!newWaiter.isCompleted)
        edt { state.initializationFailed("test initialization failure") }
        newWaiter.await()
        check(state.error?.stage == WebViewErrorStage.Initialization)
        println("PASS: previous wait does not follow replacement session")

        val early = NativeBridge.create(0, profile)
        edt { state.attached(); state.session = early; state.closing(early); state.session = 0 }
        withTimeout(5_000) { state.awaitClosed() }
        println("PASS: close during initialization")

        val runtime = WebViewClientRuntime(WebViewClient(onAcceleratorKey = {
            check(it.virtualKey == 27 && it.isKeyDown && it.isRepeat && it.control && !it.alt && !it.shift)
            true
        }))
        val key = arrayOf("key", "27", "true", "false", "true", "true", "false", "false")
        check(runtime.callback.apply(key) == "handled")
        runtime.client = WebViewClient()
        check(runtime.callback.apply(key) == "ignore")
        runtime.close()
        check(runtime.callback.apply(key) == "ignore")
        println("PASS: accelerator protocol, replacement and disposal (no real keyboard injection)")
    } finally {
        NativeBridge.closeAndWait(first); NativeBridge.closeAndWait(other)
    }
    until("all native hosts released") { NativeBridge.activeHosts() == 0 }
}

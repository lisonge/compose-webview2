package li.songe.compose.webview2.internal

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.CopyOnWriteArrayList
import li.songe.compose.webview2.*

fun main() {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val executor = Executors.newCachedThreadPool()
    server.executor = executor
    server.createContext("/") { exchange ->
        try {
            val path = exchange.requestURI.path
            if (path == "/redirect") {
                exchange.responseHeaders.add("Location", "/blocked")
                exchange.sendResponseHeaders(302, -1)
            } else {
                if (path == "/slow") Thread.sleep(4000)
                val bytes = "<title>$path</title><body>$path</body>".toByteArray()
                exchange.responseHeaders.add("Content-Type", "text/html")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.write(bytes)
            }
        } catch (_: Exception) {} finally { exchange.close() }
    }
    server.start()
    val base = "http://127.0.0.1:${server.address.port}"
    val requests = CopyOnWriteArrayList<WebViewNavigationRequest>()
    val windows = CopyOnWriteArrayList<WebViewNewWindowRequest>()
    val runtime = WebViewClientRuntime(WebViewClient(onNavigationRequest = {
        requests += it; !it.url.endsWith("/blocked")
    }))
    val id = NativeBridge.create(0, Path.of("build/navigation-smoke-profile").toAbsolutePath().toString())
    fun await(label: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (System.nanoTime() < deadline) { if (condition()) return; Thread.sleep(25) }
        error("$label: ${NativeBridge.pageState(id).toList()} / ${NativeBridge.status(id)}")
    }
    fun ready(path: String) = await(path) {
        val p = NativeBridge.pageState(id); p[0] == base + path && p[1] == path && p[2] == "false"
    }
    fun eval(script: String): String {
        NativeBridge.script(id, script)
        await("script") { NativeBridge.scriptResult(id).isNotEmpty() }
        return NativeBridge.scriptResult(id)
    }
    try {
        NativeBridge.setClient(id, runtime.callback)
        NativeBridge.navigate(id, "$base/one", false); ready("/one")
        NativeBridge.navigate(id, "$base/two", false); ready("/two")
        check(NativeBridge.pageState(id)[3] == "true")
        NativeBridge.navigationAction(id, 0); ready("/one")
        check(NativeBridge.pageState(id)[4] == "true")
        NativeBridge.navigationAction(id, 1); ready("/two")
        NativeBridge.navigate(id, "$base/blocked", false)
        await("blocked") { requests.any { it.url.endsWith("/blocked") } }
        check(NativeBridge.pageState(id)[0] == "$base/two")
        NativeBridge.navigate(id, "$base/redirect", false)
        await("redirect") { requests.any { it.isRedirect && it.url.endsWith("/blocked") } }
        await("redirect settled") { NativeBridge.pageState(id)[2] == "false" }
        eval("document.title='dynamic'; window.marker=1; true")
        await("title") { NativeBridge.pageState(id)[1] == "dynamic" }
        eval("history.pushState({},'', '/push'); true")
        await("pushState") { NativeBridge.pageState(id)[0] == "$base/push" }
        runtime.client = WebViewClient(onNewWindowRequest = { windows += it; WebViewNewWindowAction.Ignore })
        eval("window.open('$base/popup'); true")
        await("ignored window") { windows.size == 1 }
        check(eval("window.marker") == "1")
        runtime.client = WebViewClient(onNewWindowRequest = { windows += it; WebViewNewWindowAction.OpenInCurrentWebView })
        eval("window.open('$base/popup'); true"); ready("/popup")
        check(NativeBridge.activeHosts() == 1)
        NativeBridge.navigate(id, "$base/slow", false)
        await("loading") { NativeBridge.pageState(id)[2] == "true" }
        NativeBridge.navigationAction(id, 2)
        await("stopped") { NativeBridge.pageState(id)[2] == "false" }
        NativeBridge.navigate(id, "$base/one", false); ready("/one")
        eval("window.marker=2; true")
        NativeBridge.navigationAction(id, 3)
        await("reload current URL") { NativeBridge.pageState(id)[2] == "false" && eval("typeof window.marker") == "\"undefined\"" }
        runtime.client = WebViewClient(onNavigationRequest = { error("test callback failure") })
        NativeBridge.navigate(id, "$base/two", false)
        await("callback error") { NativeBridge.status(id).startsWith("Error: navigation callback") }
        check(NativeBridge.pageState(id)[0] == "$base/one")
        runtime.client = WebViewClient()
        NativeBridge.navigate(id, "$base/two", false); ready("/two")
        println("NAVIGATION OK: history, URL/title/loading, cancellation, redirects, stop/reload, window actions, callback replacement and exceptions")
    } finally {
        runtime.close(); NativeBridge.close(id); NativeBridge.navigationAction(id, 0)
        server.stop(0); executor.shutdownNow()
    }
    await("teardown") { NativeBridge.activeHosts() == 0 }
    check(runtime.callback.apply(arrayOf("navigation", base, "false", "false")) == "ignore")
    println("CLIENT LIFECYCLE OK")
}

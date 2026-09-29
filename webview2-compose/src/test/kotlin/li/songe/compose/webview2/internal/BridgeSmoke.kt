package li.songe.compose.webview2.internal

import li.songe.compose.webview2.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

private class TestApi {
    val started = AtomicInteger()
    val replies = java.util.concurrent.ConcurrentLinkedQueue<WebMessageReply>()
    @JavascriptInterface fun getVersion(): String = "1.0.0"
    @JavascriptInterface fun blocking(): String { Thread.sleep(180); return "blocked" }
    @JavascriptInterface fun echo(value: String): String = value
    @JavascriptInterface fun add(a: Int, b: Int): Int = a + b
    @JavascriptInterface fun nullable(value: String?): String? = value
    @JavascriptInterface fun json(value: JsonObject): JsonObject = value
    @JavascriptInterface fun unit() {}
    @JavascriptInterface fun failure(): String = error("sync failure")
    fun hidden(): String = "not exported"
}

private class Browser(val api: TestApi, allowed: String = "about:blank") : AutoCloseable {
    private val runtime = JavascriptBridgeRuntime(webViewBindings {
        allowOrigin(allowed)
        addJavascriptInterface(api, "App")
        addWebMessageListener("Messages") { value, reply ->
            val request = value.jsonObject
            if (request["method"]?.jsonPrimitive?.content == "pending") {
                api.replies.add(reply); api.started.incrementAndGet()
            } else {
                Thread {
                    Thread.sleep(200)
                    reply.postMessage(request)
                }.apply { isDaemon = true }.start()
            }
        }
        addDocumentStartScript("""
            let requestSequence = 0; const pending = new Map();
            Messages.onmessage = ({data}) => { pending.get(data.id)?.(data.value); pending.delete(data.id); };
            window.request = value => new Promise(resolve => {
                const id = ++requestSequence; pending.set(id, resolve); Messages.postMessage({id,value});
            });
        """.trimIndent())
        addJavascriptVariable("appConfig", buildJsonObject { put("name", "quotes '\" 中文\n") })
        addDocumentStartScript("window.documentStart = App.getVersion();")
    })
    val id = NativeBridge.createBound(0, Path.of("build", "bridge-smoke-profile").toAbsolutePath().toString(), runtime.nativeCallbacks(), runtime.initializationScript)
        .also { runtime.nativeId = it }
    fun load(html: String) = NativeBridge.navigate(id, html, true)
    fun evaluate(script: String): String {
        NativeBridge.script(id, script)
        val end = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < end) {
            NativeBridge.scriptResult(id).takeIf { it.isNotEmpty() }?.let { return it }
            Thread.sleep(10)
        }
        error("Script timed out: ${NativeBridge.status(id)}")
    }
    fun waitFor(script: String, expected: String = "true") {
        val end = System.nanoTime() + 30_000_000_000L
        while (System.nanoTime() < end) {
            val status = NativeBridge.status(id)
            check(!status.startsWith("Error")) { status }
            if (status == "Ready" && evaluate(script) == expected) return
            Thread.sleep(30)
        }
        error("Timed out: $script; ${evaluate("JSON.stringify(window.result)")}")
    }
    override fun close() { runtime.close(); NativeBridge.close(id) }
}

/** Real hidden browser; no focus, keyboard or desktop interaction. */
fun main() {
    for (callbacks in listOf(emptyMap<String, Any>(), mapOf("invoke" to "wrong callback type"))) {
        check(runCatching { NativeBridge.createBound(0, "", callbacks, "") }.exceptionOrNull() is IllegalStateException)
        check(NativeBridge.activeHosts() == 0) { "Invalid callback map created a host" }
    }
    println("CALLBACK MAP OK: incomplete or wrongly typed contracts rejected before creating native host")
    val unsupported = object { @JavascriptInterface suspend fun forbidden(): String = "no" }
    check(runCatching { webViewBindings { allowOrigin("about:blank"); addJavascriptInterface(unsupported, "Bad") } }.isFailure)
    val api = TestApi()
    Browser(api).use { browser ->
        browser.load("""<!doctype html><script>
            window.result = {};
            (async () => {
                try {
                    const r = window.result;
                    r.first = App.getVersion() === '1.0.0' && documentStart === '1.0.0';
                    r.sync = !(App.getVersion() instanceof Promise);
                    let fired = false; setTimeout(() => fired = true, 0);
                    const start = performance.now();
                    r.block = App.blocking() === 'blocked' && performance.now()-start >= 150 && !fired;
                    r.types = App.add(2,3) === 5 && App.nullable(null) === null && App.unit() === undefined && App.json({nested:[1,true]}).nested[1] === true;
                    r.string = App.echo(appConfig.name) === appConfig.name;
                    r.hidden = typeof App.hidden === 'undefined';
                    try { App.failure(); } catch(e) { r.syncError = e.message === 'sync failure'; }
                    try { App.add('2',3); } catch(e) { r.typeError = true; }
                    const promise = request('done');
                    r.promise = promise instanceof Promise;
                    r.value = (await promise) === 'done';
                    r.nonblocking = fired;
                    const values = await Promise.all([request('a'),request('b')]);
                    r.concurrent = values.join('') === 'ab';
                    r.done = true;
                } catch(e) { window.result.error = String(e); }
            })();
            </script>""")
        browser.waitFor("window.result?.done === true")
        val result = Json.parseToJsonElement(browser.evaluate("window.result")).jsonObject
        check(result.values.all { it.jsonPrimitive.boolean }) { result.toString() }
        println("BRIDGE OK: $result")
        browser.evaluate("Messages.postMessage({method:'pending'}); true")
        awaitCondition { api.started.get() == 1 }
        browser.load("<script>window.reloaded = App.getVersion() === '1.0.0' && documentStart === '1.0.0';</script>")
        browser.waitFor("window.reloaded === true")
        check(!api.replies.remove().postMessage(JsonPrimitive("stale")))
        println("NAVIGATION OK: binding reinstalled and old document reply rejected")
        Browser(TestApi()).use { second ->
            second.load("<script>window.second = App.add(7,8);</script>")
            second.waitFor("window.second === 15")
            check(browser.evaluate("window.reloaded") == "true")
        }
        browser.evaluate("Messages.postMessage({method:'pending'}); true")
        awaitCondition { api.started.get() == 2 }
    }
    check(!api.replies.remove().postMessage(JsonPrimitive("closed")))
    Browser(TestApi(), "https://example.com").use { denied ->
        denied.load("<script>window.denied = typeof App === 'undefined';</script>")
        denied.waitFor("window.denied === true")
        val response = denied.evaluate("""
            JSON.parse(chrome.webview.hostObjects.sync.__composeBridge.invoke(JSON.stringify({channel:'compose-webview2',method:'App.getVersion',args:[]}))).ok
        """.trimIndent())
        check(response == "false") { "Origin restriction bypassed: $response" }
    }
    awaitCondition { NativeBridge.activeHosts() == 0 }
    println("LIFECYCLE OK: isolated instances, origin rejection, late reply rejection and zero live hosts")
}
private fun awaitCondition(condition: () -> Boolean) {
    val deadline = System.nanoTime() + 5_000_000_000L
    while (!condition() && System.nanoTime() < deadline) Thread.sleep(20)
    check(condition()) { "Condition timed out" }
}

package li.songe.compose.webview2.sample

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.*
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Two loopback origins; the API deliberately sends no CORS permission headers. */
class SampleNetworkFixture : AutoCloseable {
    private val executor = Executors.newFixedThreadPool(4) { task -> Thread(task, "sample-http").apply { isDaemon = true } }
    private val pageServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val apiServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val pageOrigin = "http://127.0.0.1:${pageServer.address.port}"
    val apiOrigin = "http://127.0.0.1:${apiServer.address.port}"
    private val browserRequests = AtomicInteger()
    private val nativeRequests = AtomicInteger()

    init {
        pageServer.executor = executor
        apiServer.executor = executor
        pageServer.createContext("/") { exchange ->
            exchange.use {
                val html = checkNotNull(javaClass.getResource("/pages/validation.html")).readBytes()
                it.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
                it.sendResponseHeaders(200, html.size.toLong())
                it.responseBody.write(html)
            }
        }
        apiServer.createContext("/") { exchange ->
            exchange.use {
                val path = it.requestURI.path
                if (path != "/stats") {
                    if (it.requestHeaders.getFirst("Origin") != null) browserRequests.incrementAndGet()
                    else nativeRequests.incrementAndGet()
                }
                if (path == "/slow") Thread.sleep(600)
                val body = buildJsonObject {
                    put("message", "Hello from another origin / 中文")
                    put("path", path)
                    put("query", it.requestURI.rawQuery ?: "")
                    put("header", it.requestHeaders.getFirst("X-Demo") ?: "")
                    put("browserRequests", browserRequests.get())
                    put("nativeRequests", nativeRequests.get())
                    put("nested", buildJsonObject { put("enabled", true); put("items", JsonArray(listOf(JsonPrimitive(1), JsonPrimitive("two")))) })
                }.toString().toByteArray(Charsets.UTF_8)
                it.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
                it.responseHeaders.add("X-Demo-Reply", "native-fetch")
                it.sendResponseHeaders(if (path == "/missing") 404 else 200, body.size.toLong())
                runCatching { it.responseBody.write(body) } // Timeout clients may already have disconnected.
            }
        }
        pageServer.start()
        apiServer.start()
    }
    override fun close() {
        pageServer.stop(0)
        apiServer.stop(0)
        executor.shutdownNow()
    }
}

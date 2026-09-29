package li.songe.compose.webview2.sample

import kotlinx.coroutines.*
import li.songe.compose.webview2.WebMessageReply
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import li.songe.compose.webview2.JavascriptInterface
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class SampleJavascriptApi : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    fun onMessage(value: JsonElement, reply: WebMessageReply) {
        val request = value.jsonObject
        scope.launch {
            val response = try {
                val args = request.getValue("args").jsonArray
                val result = when (request.getValue("method").jsonPrimitive.content) {
                    "greet" -> { require(args.size == 1); JsonPrimitive(greet(args[0].jsonPrimitive.content)) }
                    "fetchGet" -> { require(args.size == 2); fetchGet(args[0].jsonPrimitive.content, args[1].jsonObject) }
                    else -> error("Unknown request")
                }
                buildJsonObject { put("id", request.getValue("id")); put("ok", true); put("value", result) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                buildJsonObject {
                    put("id", request.getValue("id")); put("ok", false)
                    put("error", buildJsonObject { put("name", e.javaClass.simpleName); put("message", e.message ?: "Request failed") })
                }
            }
            reply.postMessage(response)
        }
    }

    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    @JavascriptInterface
    fun getVersion(): String = "0.1.0"

    private suspend fun greet(name: String): String {
        delay(200)
        return "Hello, $name"
    }

    /** Native HTTP, independent of browser CORS, cookies and fetch Response objects. */
    private suspend fun fetchGet(url: String, options: JsonObject): JsonObject {
        require(options.keys.all { it in setOf("headers", "timeoutMs") }) { "Unknown fetchGet option" }
        val uri = URI(url)
        require(uri.scheme in setOf("http", "https") && uri.host != null && uri.userInfo == null) { "Expected HTTP(S) URL" }
        val timeout = options["timeoutMs"]?.let {
            val value = it.jsonPrimitive
            require(!value.isString) { "timeoutMs must be an integer" }
            value.long.also { number -> require(number in 100..30_000) { "timeoutMs must be 100..30000" } }
        } ?: 10_000L
        val request = HttpRequest.newBuilder(uri).timeout(Duration.ofMillis(timeout)).GET()
        options["headers"]?.jsonObject?.forEach { (name, value) ->
            require(value is JsonPrimitive && value.isString) { "Header values must be strings" }
            request.header(name, value.content)
        }
        val response = suspendCancellableCoroutine<HttpResponse<String>> { continuation ->
            val future = client.sendAsync(request.build(), HttpResponse.BodyHandlers.ofString())
            continuation.invokeOnCancellation { future.cancel(true) }
            future.whenComplete { value, error ->
                if (error == null) continuation.resume(value)
                else continuation.resumeWithException(if (error is CompletionException) error.cause ?: error else error)
            }
        }
        return buildJsonObject {
            put("url", response.uri().toString())
            put("status", response.statusCode())
            put("ok", response.statusCode() in 200..299)
            put("headers", buildJsonObject {
                response.headers().map().forEach { (key, values) -> put(key, JsonArray(values.map(::JsonPrimitive))) }
            })
            put("body", response.body())
            put("json", runCatching { Json.parseToJsonElement(response.body()) }.getOrDefault(JsonNull))
        }
    }

    override fun close() { scope.cancel(); client.shutdownNow() }
}

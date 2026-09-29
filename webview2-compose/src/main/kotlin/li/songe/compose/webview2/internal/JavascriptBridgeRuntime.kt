package li.songe.compose.webview2.internal

import li.songe.compose.webview2.*
import kotlinx.serialization.json.*
import java.lang.reflect.InvocationTargetException
import java.util.function.Function
import java.util.function.LongConsumer

/** Native calls JDK interface objects, never this class or its methods by name. */
internal class JavascriptBridgeRuntime(private val bindings: WebViewBindings) : AutoCloseable {
    @Volatile var nativeId: Long = 0
    private val lock = Any()
    private var epoch = 0L
    private var closed = false

    // Keys are protocol identifiers, not JVM method names. The JVM descriptors
    // visible to C++ contain only JDK types, so this entire class can be renamed.
    fun nativeCallbacks(): Map<String, Any> = mapOf(
        "invoke" to Function<Array<String>, String> { args ->
            require(args.size == 4) { "Invalid native invocation arguments" }
            invoke(args[0], args[1].toLong(), args[2], args[3].toBooleanStrict())
        },
        "navigated" to LongConsumer { generation -> navigated(generation) },
        "close" to Runnable { close() },
    )

    private fun navigated(generation: Long) = synchronized(lock) {
        epoch = generation
    }

    override fun close() = synchronized(lock) { closed = true }

    // JSON string for sync calls; async responses go through PostWebMessageAsJson.
    private fun invoke(request: String, generation: Long, source: String, asynchronous: Boolean): String {
        var message: JsonObject? = null
        try {
            require(request.length <= 1_048_576) { "Bridge request too large" }
            message = Json.parseToJsonElement(request).jsonObject
            require(message["channel"]?.jsonPrimitive?.content == "compose-webview2") { "Invalid bridge channel" }
            check(normalizeBridgeOrigin(source) in bindings.origins) { "Origin is not allowed" }
            synchronized(lock) { check(!closed && generation == epoch) { "Page is closed" } }
            if (asynchronous) {
                val listenerName = message.getValue("listener").jsonPrimitive.content
                val listener = bindings.listeners[listenerName] ?: error("Unknown message listener")
                val captured = message
                var replied = false
                val reply = WebMessageReply { value -> synchronized(lock) {
                    if (closed || epoch != generation || replied) false
                    else {
                        replied = true
                        reply(generation, captured, buildJsonObject { put("listener", listenerName); put("value", value) })
                        true
                    }
                } }
                listener(message.getValue("value"), reply)
                return "{}"
            }
            val method = bindings.methods[message.getValue("method").jsonPrimitive.content]
                ?: error("Unknown bridge method")
            val args = message.getValue("args").jsonArray
            val parameters = method.function.parameterTypes
            require(parameters.size == args.size) { "Argument count mismatch" }
            val values = parameters.mapIndexed { index, type -> decode(args[index], type) }.toTypedArray()
            val value = method.function.invoke(method.instance, *values)
            return success(if (method.function.returnType == Void.TYPE) Unit else value).toString()
        } catch (error: Throwable) {
            val response = failure(error)
            if (asynchronous && message != null) reply(generation, message, response)
            return response.toString()
        }
    }

    private fun reply(generation: Long, request: JsonObject, response: JsonObject) {
        val payload = JsonObject(response + mapOf(
            "channel" to JsonPrimitive("compose-webview2"),
            "document" to (request["document"] ?: JsonNull),
            "id" to (request["id"] ?: JsonNull),
        ))
        if (nativeId != 0L) NativeBridge.bridgeReply(nativeId, generation, payload.toString())
    }

    val initializationScript: String get() {
        val methods = JsonArray(bindings.methods.map { (key, method) -> buildJsonObject {
            put("key", key); put("object", method.name); put("method", method.function.name)
        } })
        val listeners = JsonArray(bindings.listeners.keys.map(::JsonPrimitive))
        val origins = JsonArray(bindings.origins.map(::JsonPrimitive))
        val variables = JsonObject(bindings.variables)
        return """
            (() => {
                if (window !== window.top) return;
                const origin = location.href === 'about:blank' ? 'about:blank' : location.origin;
                if (!$origins.includes(origin)) return;
                const documentId = Array.from(crypto.getRandomValues(new Uint32Array(4)), n => n.toString(16)).join('-');
                const listeners = Object.create(null);
                let sequence = 0;
                const unwrap = result => {
                    if (!result.ok) { const e = new Error(result.error.message); e.name = result.error.name; throw e; }
                    return result.unit ? undefined : result.value;
                };
                chrome.webview.addEventListener('message', event => {
                    const r = event.data;
                    if (!r || r.channel !== 'compose-webview2' || r.document !== documentId) return;
                    listeners[r.listener]?.onmessage?.({data:r.value});
                });
                for (const name of $listeners) {
                    const object = {onmessage:null, postMessage(value) {
                        chrome.webview.postMessage(JSON.stringify({channel:'compose-webview2', document:documentId, listener:name, value}));
                    }};
                    listeners[name] = object;
                    Object.defineProperty(window, name, {value:object});
                }
                const objects = Object.create(null);
                for (const m of $methods) {
                    const target = objects[m.object] ??= Object.create(null);
                    target[m.method] = (...args) => {
                        const request = {channel:'compose-webview2', document:documentId, id:++sequence, method:m.key, args};
                        return unwrap(JSON.parse(chrome.webview.hostObjects.sync.__composeBridge.invoke(JSON.stringify(request))));
                    };
                }
                for (const [name, object] of Object.entries(objects)) Object.defineProperty(window, name, {value:Object.freeze(object)});
                for (const [name, value] of Object.entries($variables)) Object.defineProperty(window, name, {value});
                ${bindings.scripts.joinToString("\n;\n")}
            })();
        """.trimIndent()
    }
}

private const val MAX_SAFE_INTEGER = 9007199254740991L
private fun decode(value: JsonElement, type: Class<*>): Any? {
    if (value == JsonNull && !type.isPrimitive && type != JsonElement::class.java) return null
    if (type == JsonElement::class.java) return value
    if (type == JsonObject::class.java) return value.jsonObject
    if (type == JsonArray::class.java) return value.jsonArray
    require(value != JsonNull) { "Null for non-null parameter" }
    val primitive = value.jsonPrimitive
    if (type == String::class.java) { require(primitive.isString); return primitive.content }
    require(!primitive.isString) { "Expected a JSON primitive, not a string" }
    return when (type) {
        Boolean::class.java, Boolean::class.javaObjectType -> primitive.boolean
        Byte::class.java, Byte::class.javaObjectType -> primitive.int.also { require(it in Byte.MIN_VALUE..Byte.MAX_VALUE) }.toByte()
        Short::class.java, Short::class.javaObjectType -> primitive.int.also { require(it in Short.MIN_VALUE..Short.MAX_VALUE) }.toShort()
        Int::class.java, Int::class.javaObjectType -> primitive.int
        Long::class.java, Long::class.javaObjectType -> primitive.long.also { require(it in -MAX_SAFE_INTEGER..MAX_SAFE_INTEGER) }
        Float::class.java, Float::class.javaObjectType -> primitive.float.also { require(it.isFinite()) }
        Double::class.java, Double::class.javaObjectType -> primitive.double.also { require(it.isFinite()) }
        else -> error("Unsupported parameter type")
    }
}
private fun success(value: Any?): JsonObject = buildJsonObject {
    put("ok", true)
    if (value == Unit) put("unit", true)
    else put("value", when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Number -> {
            if (value is Long) require(value in -MAX_SAFE_INTEGER..MAX_SAFE_INTEGER) { "Long exceeds JavaScript safe integer range" }
            require(value.toDouble().isFinite()) { "Non-finite number" }
            JsonPrimitive(value)
        }
        else -> error("Unsupported return value")
    })
}
private fun failure(error: Throwable): JsonObject {
    val cause = if (error is InvocationTargetException) error.targetException else error
    return buildJsonObject {
        put("ok", false)
        put("error", buildJsonObject { put("name", cause.javaClass.simpleName ?: "Error"); put("message", cause.message ?: "Native call failed") })
    }
}

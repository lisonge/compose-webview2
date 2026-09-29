package li.songe.compose.webview2

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.serialization.json.*
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.net.URI

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
public annotation class JavascriptInterface

/** Immutable bindings. Replacing this instance recreates the native browser. */
public class WebViewBindings internal constructor(
    internal val methods: Map<String, BoundMethod>,
    internal val listeners: Map<String, (JsonElement, WebMessageReply) -> Unit>,
    internal val variables: Map<String, JsonElement>,
    internal val scripts: List<String>,
    internal val origins: Set<String>,
) {
    public class Builder {
        private val objects = linkedMapOf<String, Any>()
        private val listeners = linkedMapOf<String, (JsonElement, WebMessageReply) -> Unit>()
        private val variables = linkedMapOf<String, JsonElement>()
        private val scripts = mutableListOf<String>()
        private val origins = linkedSetOf<String>()
        private fun checkName(name: String) {
            require(name.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]*"))) { "Invalid JavaScript name: $name" }
            require(name !in setOf("window", "self", "top", "parent", "chrome", "location", "document", "__proto__", "constructor")) { "Reserved name: $name" }
            require(name !in objects && name !in variables && name !in listeners) { "Duplicate binding: $name" }
        }
        public fun allowOrigin(vararg origins: String) {
            this.origins += origins.map(::normalizeBridgeOrigin)
        }
        public fun addJavascriptInterface(value: Any, name: String) { checkName(name); objects[name] = value }
        /** Receives explicit page messages; the reply is valid only for the originating document. */
        public fun addWebMessageListener(name: String, listener: (JsonElement, WebMessageReply) -> Unit) {
            checkName(name); listeners[name] = listener
        }
        public fun addJavascriptVariable(name: String, value: JsonElement) { checkName(name); variables[name] = value }
        public fun addDocumentStartScript(script: String) { scripts += script }
        public fun build(): WebViewBindings {
            require(origins.isNotEmpty()) { "Explicitly allow trusted origins before exposing bindings" }
            val methods = linkedMapOf<String, BoundMethod>()
            objects.forEach { (name, instance) ->
                instance.javaClass.methods.filter { it.isAnnotationPresent(JavascriptInterface::class.java) }.forEach { function ->
                    require(!Modifier.isStatic(function.modifiers) && function.typeParameters.isEmpty() && !function.isVarArgs) { "Export non-static, non-generic methods without varargs" }
                    require(function.parameterTypes.none { it == kotlin.coroutines.Continuation::class.java }) { "suspend exports are unsupported; use addWebMessageListener" }
                    require(function.name !in setOf("then", "__proto__", "constructor")) { "Reserved method: ${function.name}" }
                    function.parameterTypes.forEach { validateBridgeType(it, false) }
                    validateBridgeType(function.returnType, true)
                    check(function.trySetAccessible()) { "Cannot access exported method: ${function.name}" }
                    val key = "$name.${function.name}"
                    require(methods.put(key, BoundMethod(name, instance, function)) == null) { "Overloads are unsupported: $key" }
                }
            }
            return WebViewBindings(methods.toMap(), listeners.toMap(), variables.toMap(), scripts.toList(), origins.toSet())
        }
    }
}

internal data class BoundMethod(val name: String, val instance: Any, val function: Method)

public fun webViewBindings(block: WebViewBindings.Builder.() -> Unit): WebViewBindings =
    WebViewBindings.Builder().apply(block).build()

/** Captured values are rebuilt only when keys change, like remember. */
@Composable
public fun rememberWebViewBindings(vararg keys: Any?, block: WebViewBindings.Builder.() -> Unit): WebViewBindings =
    remember(*keys) { webViewBindings(block) }

internal fun normalizeBridgeOrigin(value: String): String {
    if (value == "about:blank") return value
    val uri = URI(value)
    require(uri.scheme?.lowercase() in setOf("http", "https") && uri.host != null && uri.userInfo == null) { "Expected HTTP(S) origin or about:blank" }
    val scheme = uri.scheme.lowercase()
    val port = uri.port.takeUnless { it == -1 || it == if (scheme == "https") 443 else 80 }
    return "$scheme://${uri.host.lowercase()}${port?.let { ":$it" } ?: ""}"
}

/** Reply to one page message. Returns false after navigation, disposal or an earlier reply. */
public class WebMessageReply internal constructor(private val send: (JsonElement) -> Boolean) {
    public fun postMessage(value: JsonElement): Boolean = send(value)
}

internal fun validateBridgeType(type: Class<*>, result: Boolean) {
    require(type in setOf(String::class.java, Boolean::class.java, Boolean::class.javaObjectType,
        Byte::class.java, Byte::class.javaObjectType, Short::class.java, Short::class.javaObjectType,
        Int::class.java, Int::class.javaObjectType, Long::class.java, Long::class.javaObjectType,
        Float::class.java, Float::class.javaObjectType, Double::class.java, Double::class.javaObjectType,
        JsonElement::class.java, JsonObject::class.java, JsonArray::class.java) ||
        (result && type == Void.TYPE)) { "Unsupported bridge type: $type" }
}

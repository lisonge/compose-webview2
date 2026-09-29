package li.songe.compose.webview2.internal

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path

private fun awaitCondition(label: String, condition: () -> Boolean) {
    val deadline = System.nanoTime() + 30_000_000_000L
    while (!condition()) {
        check(System.nanoTime() < deadline) { "Timeout: $label" }
        Thread.sleep(20)
    }
    println("PASS: $label")
}
fun main() {
    val id = NativeBridge.create(0, Path.of("build", "viewport-smoke-profile").toAbsolutePath().toString())
    try {
        awaitCondition("ready") { NativeBridge.status(id) == "Ready" }
        NativeBridge.navigate(id, "<body style='margin:0;background:rgb(20,80,160)'>static</body>", true)
        var sequence = 0L
        for ((revision, spec) in listOf(Triple(640,480,1.0), Triple(640,480,2.0), Triple(480,320,1.5), Triple(320,240,1.0)).withIndex()) {
            NativeBridge.resize(id, 0, 0, spec.first, spec.second, spec.third, revision + 10L)
            awaitCondition("static viewport ${spec.first}x${spec.second} @ ${spec.third}, revision ${revision+10}") {
                val bytes = NativeBridge.frame(id, sequence) ?: return@awaitCondition false
                val h = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                val w = h.int; val height = h.int; sequence = h.long
                val rev = h.long; val scale = h.double
                rev == revision + 10L && w == spec.first && height == spec.second && scale == spec.third &&
                    bytes[32 + (height / 2 * w + w / 2) * 4].toInt().and(255) == 160
            }
        }
        repeat(20) { NativeBridge.resize(id,0,0,400+it,300+it,1.0,100L+it) }
        awaitCondition("rapid resize converges to final revision") {
            val bytes = NativeBridge.frame(id, sequence) ?: return@awaitCondition false
            val h = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val w=h.int; val height=h.int; sequence=h.long
            h.long == 119L && w == 419 && height == 319
        }
        NativeBridge.navigate(id,"http://127.0.0.1:1/unreachable",false)
        awaitCondition("typed navigation failure with failing URL/code") {
            val e = NativeBridge.errorState(id)
            e.size == 6 && e[0] == "Navigation" && e[3] == "http://127.0.0.1:1/unreachable" && e[4].toIntOrNull() != null
        }
        NativeBridge.navigate(id,"about:blank",false)
        awaitCondition("navigation recovery clears error") { NativeBridge.errorState(id).isEmpty() && NativeBridge.status(id)=="Ready" }
        val client = WebViewClientRuntime(li.songe.compose.webview2.WebViewClient(onNavigationRequest = { false }))
        NativeBridge.setClient(id,client.callback)
        NativeBridge.navigate(id,"https://example.invalid/canceled",false)
        Thread.sleep(300)
        check(NativeBridge.errorState(id).isEmpty())
        client.client = li.songe.compose.webview2.WebViewClient(onNavigationRequest = { error("callback test") })
        NativeBridge.navigate(id,"https://example.invalid/callback",false)
        awaitCondition("typed callback failure") { NativeBridge.errorState(id).firstOrNull()=="Callback" }
    } finally { NativeBridge.closeAndWait(id) }
    check(NativeBridge.activeHosts() == 0)
}

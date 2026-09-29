package li.songe.compose.webview2.internal

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path

/** Real browser/capture integration test, no visible window or system input. */
fun main() {
    val profile = Path.of("build", "browser-smoke-profile").toAbsolutePath().toFile().apply { mkdirs() }
    val id = NativeBridge.create(0, profile.path)
    try {
        NativeBridge.resize(id, 0, 0, 320, 240, 1.0)
        NativeBridge.navigate(id, """<!doctype html><body style="margin:0;background:rgb(20,80,160)"><button style="width:150px;height:60px" onclick="document.body.dataset.clicks='yes'">click</button><script>let n=0;setInterval(()=>document.title='frame '+(++n),50)</script>""", true)
        val deadline = System.nanoTime() + 30_000_000_000L
        var sequence = 0L
        var verified = false
        while (System.nanoTime() < deadline) {
            val status = NativeBridge.status(id)
            check(!status.startsWith("Error")) { status }
            val bytes = NativeBridge.frame(id, sequence)
            if (bytes != null) {
                val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                val width = header.int
                val height = header.int
                sequence = header.long
                if (width == 320 && height == 240) {
                    val pixel = 32 + (200 * width + 200) * 4
                    if ((bytes[pixel].toInt() and 255) == 160 && (bytes[pixel + 1].toInt() and 255) == 80) {
                        verified = true
                        println("CAPTURE OK: ${width}x$height, sequence=$sequence, known page color matched")
                        break
                    }
                }
            }
            Thread.sleep(50)
        }
        check(verified) { "No matching page frame. status=${NativeBridge.status(id)}, sequence=$sequence" }
        NativeBridge.mouse(id, 0x201, 1, 0, 30, 30)
        NativeBridge.mouse(id, 0x202, 0, 0, 30, 30)
        Thread.sleep(300)
        NativeBridge.script(id, "document.body.dataset.clicks")
        repeat(100) { if (NativeBridge.scriptResult(id).isEmpty()) Thread.sleep(20) }
        check(NativeBridge.scriptResult(id) == "\"yes\"") { "Mouse forwarding failed: ${NativeBridge.scriptResult(id)}" }
        println("MOUSE OK: browser DOM click handler executed without focus")
    } finally { NativeBridge.close(id) }
    val deadline = System.nanoTime() + 5_000_000_000L
    while (NativeBridge.activeHosts() != 0 && System.nanoTime() < deadline) Thread.sleep(20)
    check(NativeBridge.activeHosts() == 0) { "Native host did not finish teardown" }
    println("TEARDOWN OK: no live native hosts")
}

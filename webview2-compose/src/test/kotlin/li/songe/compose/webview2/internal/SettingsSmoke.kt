package li.songe.compose.webview2.internal

import li.songe.compose.webview2.WebViewSettings
import java.nio.file.Path

fun main() {
    for (zoom in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
        check(runCatching { WebViewSettings(zoomFactor = zoom) }.isFailure)
    }
    check(runCatching { WebViewSettings(userAgent = "bad\r\nUA") }.isFailure)
    val id = NativeBridge.create(0, Path.of("build/settings-smoke-profile").toAbsolutePath().toString())
    fun eval(script: String): String {
        NativeBridge.script(id, script)
        val deadline = System.nanoTime() + 5_000_000_000L
        while (NativeBridge.scriptResult(id).isEmpty() && System.nanoTime() < deadline) Thread.sleep(20)
        return NativeBridge.scriptResult(id)
    }
    fun await(script: String) {
        val deadline = System.nanoTime() + 20_000_000_000L
        while (System.nanoTime() < deadline) {
            check(!NativeBridge.status(id).startsWith("Error")) { NativeBridge.status(id) }
            if (NativeBridge.status(id) == "Ready" && eval(script) == "true") return
            Thread.sleep(30)
        }
        error("Timed out: $script")
    }
    try {
        var settings = WebViewSettings(userAgent = "SettingsSmoke/1.0", javaScriptEnabled = false,
            userZoomEnabled = false, contextMenuEnabled = false)
        NativeBridge.configure(id, settings)
        NativeBridge.navigate(id, "<body id='first'><script>window.pageScript=true</script></body>", true)
        await("document.body.id==='first' && navigator.userAgent==='SettingsSmoke/1.0' && typeof window.pageScript==='undefined'")
        eval("window.marker='retained'; window.originalDpr=devicePixelRatio; true")
        settings = settings.copy(zoomFactor = 1.5, devToolsEnabled = true, statusBarEnabled = true)
        NativeBridge.configure(id, settings)
        await("window.marker==='retained' && Math.abs(devicePixelRatio/window.originalDpr-1.5)<0.05")
        settings = settings.copy(userAgent = null, javaScriptEnabled = true, userZoomEnabled = true, contextMenuEnabled = true)
        NativeBridge.configure(id, settings)
        NativeBridge.navigate(id, "<body id='second'><script>window.pageScript=true</script></body>", true)
        await("document.body.id==='second' && window.pageScript===true && navigator.userAgent!=='SettingsSmoke/1.0'")
        eval("window.marker='second'; true")
        NativeBridge.configure(id, settings.copy(zoomFactor = 1.0, devToolsEnabled = false, statusBarEnabled = false))
        await("window.marker==='second'")
        println("SETTINGS OK: validation, initial UA/script disable, live zoom without reload, restored UA and scripts on navigation")
    } finally { NativeBridge.close(id) }
    val deadline = System.nanoTime() + 10_000_000_000L
    while (NativeBridge.activeHosts() != 0 && System.nanoTime() < deadline) Thread.sleep(20)
    check(NativeBridge.activeHosts() == 0)
}

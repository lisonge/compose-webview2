package li.songe.compose.webview2.internal

import java.nio.file.Path

/** Real WebView2 profile theme checks without visible windows or focus. */
fun main() {
    fun awaitValue(id: Long, script: String, expected: String) {
        val deadline = System.nanoTime() + 30_000_000_000L
        while (System.nanoTime() < deadline) {
            check(!NativeBridge.status(id).startsWith("Error")) { NativeBridge.status(id) }
            if (NativeBridge.status(id) == "Ready") {
                NativeBridge.script(id, script)
                val resultDeadline = System.nanoTime() + 2_000_000_000L
                while (NativeBridge.scriptResult(id).isEmpty() && System.nanoTime() < resultDeadline) Thread.sleep(20)
                if (NativeBridge.scriptResult(id) == expected) return
            }
            Thread.sleep(30)
        }
        error("Expected $expected for $script; got ${NativeBridge.scriptResult(id)}")
    }
    val profile = Path.of("build", "theme-smoke-profile").toAbsolutePath().toString()
    val html = """<!doctype html><style>body {color:rgb(10,20,30)} @media(prefers-color-scheme:dark){body{color:rgb(200,210,220)}}</style><body><script>
        window.initialDark=matchMedia('(prefers-color-scheme:dark)').matches;
        window.marker='original'; window.changes=0;
        matchMedia('(prefers-color-scheme:dark)').addEventListener('change',()=>window.changes++);
        </script></body>"""
    val dark = "matchMedia('(prefers-color-scheme:dark)').matches"
    val id = NativeBridge.create(0, profile, 2)
    var peer = 0L
    var isolated = 0L
    try {
        NativeBridge.navigate(id, html, true)
        awaitValue(id, "window.initialDark", "true")
        awaitValue(id, "getComputedStyle(document.body).color", "\"rgb(200, 210, 220)\"")
        NativeBridge.colorScheme(id, 1)
        awaitValue(id, dark, "false")
        awaitValue(id, "getComputedStyle(document.body).color", "\"rgb(10, 20, 30)\"")
        awaitValue(id, "window.initialDark && window.marker==='original' && window.changes>0", "true")
        peer = NativeBridge.create(0, profile, 1)
        NativeBridge.navigate(peer, html, true)
        awaitValue(peer, "window.initialDark", "false")
        isolated = NativeBridge.create(0, "$profile-isolated", 1)
        NativeBridge.navigate(isolated, html, true)
        awaitValue(isolated, "window.initialDark", "false")
        NativeBridge.colorScheme(id, 2)
        awaitValue(id, dark, "true")
        awaitValue(peer, dark, "true")
        awaitValue(isolated, dark, "false")
        NativeBridge.colorScheme(id, 0)
        awaitValue(id, "window.marker", "\"original\"")
        println("THEME OK: initial CSS, live media change, no reload, shared profile and isolated directory; Auto accepted")
    } finally {
        NativeBridge.close(id)
        if (peer != 0L) NativeBridge.close(peer)
        if (isolated != 0L) NativeBridge.close(isolated)
        NativeBridge.colorScheme(id, 2) // Closed sessions ignore updates.
    }
    val deadline = System.nanoTime() + 10_000_000_000L
    while (NativeBridge.activeHosts() != 0 && System.nanoTime() < deadline) Thread.sleep(20)
    check(NativeBridge.activeHosts() == 0)
    println("THEME TEARDOWN OK")
}

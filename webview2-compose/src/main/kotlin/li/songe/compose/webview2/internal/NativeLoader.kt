package li.songe.compose.webview2.internal

import java.nio.file.Files

internal object NativeLoader {
    @Synchronized
    fun load() {
        if (loaded) return
        check(System.getProperty("os.name").startsWith("Windows")) { "Only Windows is supported" }
        check(System.getProperty("os.arch") in setOf("amd64", "x86_64")) { "Only x64 is supported" }
        val directory = Files.createTempDirectory("compose-webview2-").toFile()
        directory.deleteOnExit()
        val dll = directory.resolve("compose_webview2.dll")
        dll.deleteOnExit()
        checkNotNull(javaClass.getResourceAsStream("/native/windows-x64/compose_webview2.dll")) {
            "Native DLL missing; run :webview2-compose:buildNative"
        }.use { input -> dll.outputStream().use { input.copyTo(it) } }
        System.load(dll.absolutePath)
        loaded = true
    }
    private var loaded = false
}

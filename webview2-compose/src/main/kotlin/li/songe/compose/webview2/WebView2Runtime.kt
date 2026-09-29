package li.songe.compose.webview2

import li.songe.compose.webview2.internal.NativeBridge

/** WebView2 Loader queries; no Compose composition, AWT window or browser is created. */
public object WebView2Runtime {
    /**
     * Returns the available browser version, or null when the loader finds no runtime.
     * May include an Edge preview channel suffix; this is not an inventory of installations.
     * Uses the loader's default search and environment/policy overrides, like WebView creation.
     * Does not guarantee that graphics capture or browser initialization will succeed.
     * Other loader errors throw IllegalStateException; native loading can throw LinkageError.
     * Supported on Windows x64 with this library's bundled native DLL and desktop JDK.
     */
    @JvmStatic
    public fun getAvailableVersion(): String? = NativeBridge.availableBrowserVersion()
}

package li.songe.compose.webview2

import li.songe.compose.webview2.internal.NativeBridge

/** Prototype diagnostics for integration tests; count includes hosts finishing asynchronous teardown. */
public object WebViewDiagnostics {
    public val activeNativeHosts: Int get() = NativeBridge.activeHosts()
}

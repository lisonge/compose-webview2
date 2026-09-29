# compose-webview2

[![Maven Central](https://img.shields.io/maven-central/v/li.songe.webview2/webview2-compose.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/li.songe.webview2/webview2-compose)

English | [简体中文](README.zh.md)

Embed WebView2 in Compose Desktop on Windows x64.

## Add the dependency

Add Maven Central to your application's repositories:

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("li.songe.webview2:webview2-compose:0.1.0")
}
```

ProGuard rules are included.

## Check WebView2 availability

No Compose composition or window is required:

```kotlin
import li.songe.compose.webview2.WebView2Runtime

val version = WebView2Runtime.getAvailableVersion()
if (version == null) {
    // Ask the user to install the WebView2 Runtime.
} else {
    println("Available WebView2 browser: $version")
}
```

This queries the WebView2 Loader without creating a browser. It returns null only when no available runtime is found; other query errors throw. The result may identify an Edge preview channel and follows loader environment/policy overrides, so it is not a list of installed runtimes or a guarantee that browser initialization will succeed. Supported on Windows x64 with the desktop JDK; no automatic installation is performed.

## Display a webpage

```kotlin
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import li.songe.compose.webview2.WebView
import li.songe.compose.webview2.rememberWebViewState

@Composable
fun Browser() {
    val state = rememberWebViewState("https://example.com")
    WebView(state, Modifier.fillMaxSize())
}
```

Use normal Compose modifiers for sizing, clipping and opacity. Place Compose overlays, Popup and Dialog as usual.

## State and placeholders

`state.lifecycle` is `Detached / Initializing / Active / Closing / Closed`. `state.error` provides an error stage and available HRESULT, failing URL, navigation error code or browser process failure kind. Canceled navigation is not a load error.

WebView draws no built-in loading or error text. Overlay normal Compose content:

```kotlin
Box {
    WebView(state, Modifier.fillMaxSize())
    state.error?.let { Text(it.message, Modifier.align(Alignment.Center)) }
}
```

`state.requestedViewport` and `state.frameViewport` contain pixel dimensions, scale and a revision. `state.isViewportReady` indicates receipt of a matching frame, not completion of page loading or animations. Resizing retains the previous image until a matching frame arrives.

## Browser accelerator keys

```kotlin
val scope = rememberCoroutineScope()
val client = remember {
    WebViewClient(onAcceleratorKey = { event ->
        if (event.virtualKey == 27 && event.isKeyDown) { // Escape
            if (!event.isRepeat) scope.launch { /* update application UI */ }
            true // consume this event
        } else false
    })
}
WebView(state, client = client)
```

The callback runs on the browser STA and must return immediately; dispatch UI work to a UI coroutine. Events contain Windows virtual-key codes, key down/up, repeat and Ctrl/Alt/Shift state. This handles browser accelerator keys, not general text or IME input.

## Browser settings

Configure through `WebViewSettings`:

| Property | Default | Purpose |
|---|---|---|
| `userAgent` | `null` | Request UA; null restores the original browser UA |
| `javaScriptEnabled` | `true` | Page script execution; effective on next navigation |
| `zoomFactor` | `1.0` | Whole-page scale, immediate; finite positive values only |
| `userZoomEnabled` | `false` | Allow user zoom |
| `devToolsEnabled` | `false` | Allow users to open DevTools through menus or shortcuts |
| `contextMenuEnabled` | `false` | Show browser context menus |
| `statusBarEnabled` | `false` | Show browser status hints, such as link URLs |

Updates do not automatically reload or recreate the page. UA affects subsequent requests; treat script and browser UI toggles as effective on the next navigation, calling `state.reload()` when needed. Disabling user zoom does not block programmatic `zoomFactor`. Disabling page scripts does not disable host script execution or replace bridge origin checks. Native menus and DevTools are browser UI, not Compose overlays; foreground interaction remains unverified.

## Page color scheme

`WebViewSettings.preferredColorScheme` supports `Auto` (default, follows the OS), `Light`, and `Dark`. Use your application theme state for `darkTheme`.

```kotlin
WebView(
    state = state,
    settings = WebViewSettings(
        preferredColorScheme =
            if (darkTheme) WebViewColorScheme.Dark else WebViewColorScheme.Light,
    ),
)
```

Updates do not recreate the browser or reload the page. Pages respond through CSS `@media (prefers-color-scheme: dark)`; sites without dark styles are not forcibly recolored. This is a profile setting: WebViews sharing a user data directory share the theme, and the last applied setting wins. Use separate directories for independent themes.

## Load HTML and navigate

```kotlin
val state = rememberWebViewHtmlState("<h1>Hello, WebView2</h1>")
WebView(state, Modifier.fillMaxSize())
```

Import `rememberWebViewHtmlState` from `li.songe.compose.webview2`. Each state belongs to one mounted WebView. To navigate from a button callback or effect:

```kotlin
state.loadUrl("https://example.com")
state.loadHtml("<h1>Updated page</h1>")
state.reload()
```

The initial URL or HTML is remembered; changing the argument on recomposition does not navigate. Use the state methods instead. Read `state.status` for loading and error information.

## History navigation and page state

```kotlin
state.goBack()
state.goForward()
state.stopLoading()
state.reload()
```

`state.canGoBack`, `state.canGoForward`, `state.url`, `state.title`, and `state.isLoading` are observable Compose state. Back/forward do nothing without corresponding history; `reload()` reloads the actual current page. Back, forward and stop are no-ops when detached.

## Navigation interception and new windows

Handle requests with `WebViewClient`:

```kotlin
val client = remember {
    WebViewClient(
        onNavigationRequest = { request ->
            !request.url.startsWith("https://blocked.example/")
        },
        onNewWindowRequest = { request ->
            WebViewNewWindowAction.OpenInCurrentWebView
        },
    )
}
WebView(state = state, client = client)
```

`onNavigationRequest` returns `true` to allow or `false` to cancel. Requests include `url`, `isUserInitiated`, and `isRedirect`; redirects are intercepted too. Only top-level navigation is handled.

`onNewWindowRequest` includes `url` and `isUserInitiated`. The default is `Ignore`, so no native popup opens. `OpenInCurrentWebView` navigates the current view and passes through navigation interception again. To open externally, dispatch work to an application-owned scope and return `Ignore`; the library does not launch external programs or preserve window.opener semantics.

Both callbacks run synchronously on the browser STA and must return promptly. Do not block waiting for UI or JavaScript; dispatch UI work to your application scope. Exceptions cancel the request and report through `state.status`. Replacing the client does not recreate the browser. Page state is synchronized on Compose frames and may briefly lag native events.

## Expose Kotlin methods to JavaScript

Annotate public methods with `@JavascriptInterface`. Calls are synchronous. Annotated `suspend` methods are rejected; use explicit messages for asynchronous work.

```kotlin
import li.songe.compose.webview2.JavascriptInterface

class AppBridge {
    @JavascriptInterface
    fun getVersion(): String = "1.0.0"
}
```

Register the object when creating the WebView:

```kotlin
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import li.songe.compose.webview2.*

@Composable
fun AppBrowser() {
    val bindings = rememberWebViewBindings {
        allowOrigin("https://example.com")
        addJavascriptInterface(AppBridge(), "App")
        addJavascriptVariable("appConfig", buildJsonObject {
            put("platform", "windows")
            put("darkMode", true)
        })
        addDocumentStartScript("window.appVersion = App.getVersion();")
    }
    val state = rememberWebViewState("https://example.com")
    WebView(state, Modifier.fillMaxSize(), bindings = bindings)
}
```

In your webpage:

```javascript
const version = App.getVersion(); // string; blocks JavaScript until Kotlin returns
console.log(appConfig.platform, appVersion);
```

Only annotated methods are exposed. Kotlin exceptions throw JavaScript errors. Method overloading is not supported.

Synchronous methods execute on the browser's native thread. They must not synchronously wait for the Compose UI thread or call back into JavaScript.

Supported values are `String`, `Boolean`, `Byte`, `Short`, `Int`, `Long` within JavaScript's safe integer range, finite `Float`/`Double`, `JsonElement`, `JsonObject`, `JsonArray`, and boxed nullable numeric/boolean variants. Java reflection does not infer Kotlin nullability: JSON null is accepted for reference parameters, while primitive parameters reject null. Kotlin non-null checks can still throw inside a method. `Unit` becomes `undefined`. Pass complex data explicitly as JSON. Supply all arguments, including Kotlin parameters with defaults.

## Inject variables and startup scripts

`addJavascriptVariable` creates a JSON snapshot on `window`, accessible before the page's first script. `addDocumentStartScript` runs after variables and methods are registered, before the page's scripts. Both are reapplied on navigation and reload. Startup scripts should not assume the DOM is already built.

Bindings are immutable. Pass keys to `rememberWebViewBindings(key1, ...)` to rebuild them when captured configuration changes; a new bindings instance recreates the browser and resets page state. Injected variables do not automatically track Kotlin changes.

Explicitly allow each trusted HTTP(S) origin. For trusted HTML loaded with `rememberWebViewHtmlState` or `loadHtml`, use `allowOrigin("about:blank")`. Bindings are installed in the top-level document; iframe registration is not supported. Do not load untrusted HTML under an allowed origin.

Allow multiple origins in one call: `allowOrigin("https://example.com", "https://api.example.com")`.

## Asynchronous messages

Register a listener and launch work in an application-owned scope:

```kotlin
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import li.songe.compose.webview2.*

private suspend fun getVersion(): String {
    delay(100) // Simulate asynchronous work.
    return "1.0.0"
}

@Composable
fun MessageBrowser() {
    val scope = rememberCoroutineScope()
    val bindings = rememberWebViewBindings(scope) {
        allowOrigin("https://example.com")
        addWebMessageListener("Messages") { value, reply ->
            scope.launch {
                val version = getVersion()
                reply.postMessage(buildJsonObject {
                    put("id", value.jsonObject.getValue("id"))
                    put("version", version)
                })
            }
        }
    }
    val state = rememberWebViewState("https://example.com")
    WebView(state, Modifier.fillMaxSize(), bindings = bindings)
}
```

In the webpage:

```javascript
Messages.onmessage = ({data}) => console.log(data.id, data.version);
Messages.postMessage({id: "request-1"});
```

A listener runs on the browser STA: dispatch work promptly, without blocking it. Each reply is single-use and returns false after navigation, disposal or an earlier reply. Business coroutine cancellation, errors and timeouts belong to the application; navigation invalidates replies but does not cancel application work. Use a scope that is cancelled when its owner closes.

For Promise wrapping with request IDs, errors and timeouts, see the sample's [bridge.js](webview2-sample/src/main/resources/pages/bridge.js) and [message handler](webview2-sample/src/main/kotlin/li/songe/compose/webview2/sample/SampleJavascriptApi.kt). No additional HTTP server is required by the bridge.

## Execute JavaScript in the current page

For one-off operations after the page loads, call the suspending `evaluateJavaScript` method from a coroutine, for example inside `rememberCoroutineScope().launch`:

```kotlin
val result = state.evaluateJavaScript("document.title")
```

Its result is JSON-encoded; use `Json.parseToJsonElement(result)` to decode it. This method is separate from the object bridge. Functions and variables created this way must be reinjected after navigation.

## Close the window

Set `WebView(..., running = false)` to release the browser while keeping its last frame visible. Call `state.awaitClosed()` from a UI coroutine to wait for this instance to finish native cleanup, then close the window. Keep the WebView mounted and its parent window alive while waiting. See the [sample close handler](webview2-sample/src/main/kotlin/li/songe/compose/webview2/sample/Main.kt).

`awaitClosed()` does not initiate closing. It is safe immediately after setting `running = false`, before recomposition. It waits only for the session present at invocation, independently of other WebViews or later sessions. Canceling the waiter does not cancel cleanup.

Setting `running` back to `true` creates a new browser session; it does not restore the previous page state.

## Portable Windows sample

Download the `windows-x64-portable.zip` asset from a published GitHub Release, extract the entire folder, and run `WebView2Sample.exe`. Keep its `app` and `runtime` directories alongside the EXE. Java is bundled; the WebView2 Runtime must be installed. Browser data is stored in `data/profile` next to the EXE. See [build and release instructions](docs/publishing.md).

## Run the sample

```powershell
.\gradlew.bat :webview2-sample:run
```

See [project documentation](docs/README.md) for setup, implementation details and validation status.

## Try native cross-origin requests

The sample includes a native `fetchGet` bridge. Click **Compare browser fetch / App.fetchGet** in the page to compare a browser CORS failure with a successful Kotlin HTTP request to the same API:

```javascript
const response = await App.fetchGet(apiOrigin + "/data", {
    headers: { "X-Demo": "from-JavaScript" },
    timeoutMs: 5000
});
console.log(response.status, response.headers, response.json);
```

`fetchGet(url: String, options: JsonObject): JsonObject` is a private suspend helper, explicitly called by the message handler in the sample's [SampleJavascriptApi](webview2-sample/src/main/kotlin/li/songe/compose/webview2/sample/SampleJavascriptApi.kt). It returns `{url, status, ok, headers, body, json}`; headers contain arrays of strings, and `json` is null when the body is not valid JSON. HTTP errors such as 404 resolve with `ok: false`; network errors and timeouts reject. Pass `{}` when no options are needed.

Requests run in Kotlin and do not share browser cookies or browser CORS enforcement. This sample supports HTTP(S) GET, does not follow redirects, and buffers the response as text; it is not a streaming or binary download API. Only the sample's trusted page origin is permitted to invoke the bridge.

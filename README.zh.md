# compose-webview2

[English](README.md) | 简体中文

在 Windows x64 的 Compose Desktop 中嵌入 WebView2。

## 添加依赖

在应用的仓库配置中添加 Maven Central：

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("li.songe.webview2:webview2-compose:0.1.0")
}
```

已内置 ProGuard 规则。

## 检测 WebView2 是否可用

无需创建 Compose 界面或窗口：

```kotlin
import li.songe.compose.webview2.WebView2Runtime

val version = WebView2Runtime.getAvailableVersion()
if (version == null) {
    // 提示用户安装 WebView2 Runtime。
} else {
    println("可用的 WebView2 浏览器版本：$version")
}
```

此方法通过 WebView2 Loader 查询，不创建浏览器。仅在未找到可用 Runtime 时返回 `null`，其他查询错误会抛出异常。结果可能包含 Edge 预览渠道信息，并遵循 Loader 的环境变量和策略覆盖设置，因此它不是已安装 Runtime 的完整列表，也不保证浏览器一定能初始化成功。支持 Windows x64 和桌面 JDK，不会自动安装 Runtime。

## 显示网页

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

使用普通 Compose Modifier 设置尺寸、裁剪和透明度。Compose 覆盖内容、Popup 和 Dialog 按常规方式使用即可。

## 状态与占位内容

`state.lifecycle` 表示 `Detached / Initializing / Active / Closing / Closed`。`state.error` 提供错误阶段，以及可用的 HRESULT、失败 URL、导航错误码或浏览器进程错误类型；主动取消导航不算加载错误。

WebView 自身不绘制加载或错误文字，可使用普通 Compose 内容叠加：

```kotlin
Box {
    WebView(state, Modifier.fillMaxSize())
    state.error?.let { Text(it.message, Modifier.align(Alignment.Center)) }
}
```

`state.requestedViewport` 与 `state.frameViewport` 包含像素尺寸、缩放和版本号；`state.isViewportReady` 表示已经收到当前视口的匹配帧，不表示页面加载或动画已结束。调整尺寸时保留上一张画面，匹配帧到达后替换。

## 浏览器快捷键

```kotlin
val scope = rememberCoroutineScope()
val client = remember {
    WebViewClient(onAcceleratorKey = { event ->
        if (event.virtualKey == 27 && event.isKeyDown) { // Escape
            if (!event.isRepeat) scope.launch { /* 更新应用界面 */ }
            true // 消费此次按键
        } else false
    })
}
WebView(state, client = client)
```

回调在浏览器 STA 上执行，需立即返回；界面操作分派至 UI 协程。事件包含 Windows 虚拟键码、按下/抬起、重复和 Ctrl/Alt/Shift 状态。此接口处理浏览器 accelerator keys，不是普通文字或 IME 输入监听器。

## 浏览器配置

通过 `WebViewSettings` 配置：

| 参数                 | 默认值  | 作用                                      |
| -------------------- | ------- | ----------------------------------------- |
| `userAgent`          | `null`  | 请求使用的 UA；null 恢复浏览器默认 UA     |
| `javaScriptEnabled`  | `true`  | 是否执行网页脚本；下次导航生效            |
| `zoomFactor`         | `1.0`   | 整页缩放，立即生效，必须为有限正数        |
| `userZoomEnabled`    | `false` | 是否允许用户缩放                          |
| `devToolsEnabled`    | `false` | 是否允许用户通过菜单或快捷键打开 DevTools |
| `contextMenuEnabled` | `false` | 是否显示浏览器默认右键菜单                |
| `statusBarEnabled`   | `false` | 是否显示链接地址等浏览器状态提示          |

设置更新不会自动刷新或重建页面。UA 影响后续请求；脚本和浏览器 UI 开关按下次导航生效处理，需要时主动调用 `state.reload()`。`userZoomEnabled=false` 不阻止程序设置 `zoomFactor`。关闭网页脚本不会禁用应用执行脚本或取代桥接的 origin 权限控制。原生菜单和 DevTools 是浏览器 UI，不是 Compose 弹层；其前台交互仍待验收。

## 网页主题

`WebViewSettings.preferredColorScheme` 支持 `Auto`（默认，跟随系统）、`Light` 和 `Dark`。`darkTheme` 使用应用当前的主题状态。

```kotlin
WebView(
    state = state,
    settings = WebViewSettings(
        preferredColorScheme =
            if (darkTheme) WebViewColorScheme.Dark else WebViewColorScheme.Light,
    ),
)
```

切换时不会重建浏览器或刷新页面。网页通过 CSS `@media (prefers-color-scheme: dark)` 响应主题；不会强制转换不支持深色模式的网站。此设置属于 profile：共享用户数据目录的 WebView 共享主题，以最后应用的设置为准；需要独立主题时使用不同目录。

## 加载 HTML 和页面导航

```kotlin
val state = rememberWebViewHtmlState("<h1>Hello, WebView2</h1>")
WebView(state, Modifier.fillMaxSize())
```

从 `li.songe.compose.webview2` 导入 `rememberWebViewHtmlState`。每个 state 只能关联一个已挂载的 WebView。在按钮回调或 effect 中调用：

```kotlin
state.loadUrl("https://example.com")
state.loadHtml("<h1>Updated page</h1>")
state.reload()
```

初始 URL 或 HTML 会被记住，重组时修改传入参数不会触发导航；请使用 state 方法。可以通过 `state.status` 读取加载和错误信息。

## 历史导航与页面状态

```kotlin
state.goBack()
state.goForward()
state.stopLoading()
state.reload()
```

`state.canGoBack`、`state.canGoForward`、`state.url`、`state.title` 和 `state.isLoading` 是可观察的 Compose 状态。返回/前进在无对应历史时不操作；`reload()` 刷新当前实际页面。未挂载时返回、前进和停止加载不操作。

## 导航拦截与新窗口

通过 `WebViewClient` 接收请求：

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

`onNavigationRequest` 返回 `true` 允许导航，`false` 取消。请求包含 `url`、`isUserInitiated` 和 `isRedirect`；重定向也会经过拦截，仅处理顶层导航。

`onNewWindowRequest` 包含 `url`、`isUserInitiated`。默认 `Ignore`，不会自动弹出原生窗口；`OpenInCurrentWebView` 会在当前页导航并再次经过导航拦截。外部打开时，由应用把操作投递到自己的作用域并返回 `Ignore`，库不自动启动外部程序，也不提供 window.opener 语义。

两个回调均在浏览器 STA 线程同步执行，必须快速返回，不能阻塞等待 UI 或 JavaScript。UI 操作请使用应用管理的作用域分派。回调抛异常时取消请求，并通过 `state.status` 报错。替换 client 不会重建浏览器；页面状态在 Compose 帧上同步，可能稍晚于原生事件。

## 在 JavaScript 中调用 Kotlin 方法

用 `@JavascriptInterface` 标注公开方法。调用是同步的；不支持导出带此注解的 `suspend` 方法，异步工作请使用显式消息机制。

```kotlin
import li.songe.compose.webview2.JavascriptInterface

class AppBridge {
    @JavascriptInterface
    fun getVersion(): String = "1.0.0"
}
```

创建 WebView 时注册对象：

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

网页中调用：

```javascript
const version = App.getVersion(); // 返回字符串；Kotlin 返回前会阻塞 JavaScript
console.log(appConfig.platform, appVersion);
```

只会导出带注解的方法。Kotlin 异常会转为 JavaScript 错误。不支持方法重载。

同步方法在浏览器原生线程执行，不得同步等待 Compose UI 线程，也不得回调 JavaScript。

支持的值类型包括 `String`、`Boolean`、`Byte`、`Short`、`Int`、处于 JavaScript 安全整数范围内的 `Long`、有限值 `Float`/`Double`、`JsonElement`、`JsonObject`、`JsonArray`，以及装箱的可空数值和布尔类型。JSON 类型来自 `kotlinx.serialization.json`。Java 反射不推断 Kotlin 的可空性：引用类型参数接受 JSON `null`，基本类型参数拒绝 `null`；Kotlin 非空检查仍可能在方法内部抛出异常。`Unit` 返回值映射为 `undefined`。复杂数据请显式使用 JSON。必须传入全部参数，包括 Kotlin 中声明了默认值的参数。

## 注入变量和启动脚本

`addJavascriptVariable` 在 `window` 上创建 JSON 快照，页面第一个脚本执行前即可访问。`addDocumentStartScript` 在变量和方法注册后、页面脚本执行前运行。导航和刷新时都会重新注入；启动脚本不能假定 DOM 已构建完成。

bindings 不可变。捕获的配置发生变化时，可通过 `rememberWebViewBindings(key1, ...)` 传入 key 以重新构建。新的 bindings 实例会重建浏览器并重置页面状态。注入变量不会自动跟随 Kotlin 数据变化。

必须显式允许每个可信 HTTP(S) origin。使用 `rememberWebViewHtmlState` 或 `loadHtml` 加载可信 HTML 时，配置 `allowOrigin("about:blank")`。绑定仅安装到顶层文档，不支持 iframe 注册。不要在已允许的 origin 下加载不可信 HTML。

可以一次允许多个来源：`allowOrigin("https://example.com", "https://api.example.com")`。

## 异步消息

注册监听器，并在应用管理的协程作用域中启动工作：

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
    delay(100) // 模拟异步操作。
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

网页中：

```javascript
Messages.onmessage = ({ data }) => console.log(data.id, data.version);
Messages.postMessage({ id: 'request-1' });
```

监听器在浏览器 STA 线程执行，应及时分派工作，避免阻塞。每个 reply 只能使用一次；导航、销毁或已经回复后，`postMessage` 返回 `false`。业务协程的取消、错误和超时由应用处理；导航会使回复失效，但不会取消业务工作。请使用随所属组件关闭而取消的作用域。

包含请求 ID、错误和超时处理的 Promise 封装见 sample 的 [bridge.js](webview2-sample/src/main/resources/pages/bridge.js) 和[消息处理器](webview2-sample/src/main/kotlin/li/songe/compose/webview2/sample/SampleJavascriptApi.kt)。桥接本身不需要额外启动 HTTP 服务。

## 在当前页面执行 JavaScript

页面加载后的临时操作可以在协程中调用 `evaluateJavaScript`，例如在 `rememberCoroutineScope().launch` 中：

```kotlin
val result = state.evaluateJavaScript("document.title")
```

结果经过 JSON 编码，可以使用 `Json.parseToJsonElement(result)` 解码。此方法独立于对象桥接机制。通过它创建的函数和变量在导航后需要重新注入。

## 关闭窗口

设置 `WebView(..., running = false)` 可释放浏览器，同时保留最后一帧。在 UI 协程中调用 `state.awaitClosed()` 等待该实例的原生清理完成，然后再关闭窗口；等待期间保持 WebView 挂载及其父窗口存活。参见 [sample 关闭处理](webview2-sample/src/main/kotlin/li/songe/compose/webview2/sample/Main.kt)。

`awaitClosed()` 不主动关闭实例；可以紧接着设置 `running = false` 调用，无需等待重组。它只等待调用时的会话，不受其他 WebView 或后续新会话影响；取消等待不会取消清理。

将 `running` 改回 `true` 会创建新的浏览器会话，不会恢复此前的页面状态。

## Windows 绿色版示例

从已发布的 GitHub Release 下载 `windows-x64-portable.zip` 附件，完整解压后运行 `WebView2Sample.exe`。保留 EXE 旁的 `app` 和 `runtime` 目录。包内自带 Java，仍需安装 WebView2 Runtime。浏览器数据保存在 EXE 旁的 `data/profile` 中。详见[构建与发布说明](docs/publishing.md)。

## 运行示例

```powershell
.\gradlew.bat :webview2-sample:run
```

环境配置、实现细节和验收状态见[项目文档](docs/README.md)。

## 尝试原生跨域请求

sample 提供原生 `fetchGet` 桥接。点击网页中的 **Compare browser fetch / App.fetchGet**，可以对比同一 API 的浏览器 CORS 失败与 Kotlin HTTP 请求成功：

```javascript
const response = await App.fetchGet(apiOrigin + '/data', {
  headers: { 'X-Demo': 'from-JavaScript' },
  timeoutMs: 5000,
});
console.log(response.status, response.headers, response.json);
```

`fetchGet(url: String, options: JsonObject): JsonObject` 是 sample 中的私有 suspend 辅助方法，由 [SampleJavascriptApi](webview2-sample/src/main/kotlin/li/songe/compose/webview2/sample/SampleJavascriptApi.kt) 的消息处理器显式调用。它返回 `{url, status, ok, headers, body, json}`；headers 的值为字符串数组，响应体不是有效 JSON 时 `json` 为 `null`。404 等 HTTP 错误正常返回，并设置 `ok: false`；网络错误和超时会拒绝 Promise。无需选项时传入 `{}`。

请求在 Kotlin 中执行，不共享浏览器 Cookie，也不受浏览器 CORS 机制限制。此示例支持 HTTP(S) GET，不跟随重定向，完整缓冲响应文本，不是流式或二进制下载 API。只有 sample 的可信页面 origin 可以调用桥接。

# 不抢焦点的后台验收

在仓库根目录启动：

```powershell
.\gradlew.bat :webview2-sample:run --args="--test"
```

另一个终端执行：

```powershell
.\scripts\verify-background.ps1
.\scripts\verify-close-frame.ps1
```

示例窗口在屏幕外运行。sample 专属 guard 在窗口显示前禁止激活，并对后来创建的 Popup/Dialog 同样生效。浏览器 `requestNativeFocus=false`。HTTP 操作只投递到应用自己的 AWT/Compose 事件接收器，不调用 Robot、SendInput、SetForegroundWindow，不移动系统鼠标。

服务仅在测试模式启用，绑定 `127.0.0.1:18765`。可通过 `WEBVIEW2_TEST_PORT` 更改端口。每次启动生成 token，位于 `webview2-sample/build/http-token`，所有请求须带 `X-Test-Token`；不要把该目录提交到 Git。

| 方法 / 路径 | 作用 |
|---|---|
| GET `/state` | 加载状态、帧数、尺寸、控件边界、点击计数、活跃原生宿主、窗口激活计数 |
| GET `/screenshot` | 从 Compose/Skia 绘制记录生成 PNG，包含网页和 Compose 弹层 |
| POST `/pointer` | JSON `x`、`y`：截图物理像素坐标，投递点击；可加 `wheel` |
| POST `/text` | 向 Compose 键盘 listener 投递文字，仅用于独立 Compose 输入框测试 |
| POST `/control` | JSON 控制 popup/dialog/menu/mounted/opacity/width/reload；用于场景设置 |
| POST `/evaluate` | 文本 JavaScript，返回浏览器 JSON 结果，用于观察 DOM 计数 |
| POST `/quit` | 退出当前示例并释放浏览器 |

`/control` 设置弹层状态不代表已测试点击；验收脚本另外通过 `/pointer` 点击标准弹层中的按钮，并断言网页 DOM 计数不变。`/evaluate` 不作为真实键盘或 IME 验收的替代。

输出在 `webview2-sample/build/evidence/`：base、popup、dialog、menu、compose-input、opacity、resized、final 的 PNG，以及 `report.json`。真实系统焦点、输入法候选窗和操作系统模态行为无法通过这种无焦点测试完整证明，必须另行验收。

`verify-close-frame.ps1` 额外生成 before-close/during-close 截图，比较网页区域在原生关闭前后的像素，并在完成后退出示例。它使用测试专属 `/quit?hold=true` 保留关闭检查点，再用普通 `/quit` 释放；交互模式没有该检查点，也不会为测试增加关闭延迟。

关闭服务：

```powershell
$headers = @{ 'X-Test-Token' = (Get-Content -LiteralPath 'webview2-sample/build/http-token' -Raw) }
Invoke-RestMethod -Uri 'http://127.0.0.1:18765/quit' -Method Post -Headers $headers
```

## Native fetchGet 验收

sample 启动两个随机端口的 loopback HTTP 服务：一个提供验证网页，另一个提供不含 Access-Control-Allow-Origin 的 JSON API。它们与带 token 的 Ktor 控制接口相互独立，交互模式也可点击页面按钮体验。

启动 `:webview2-sample:run --args="--test"` 后运行：

```powershell
.\scripts\verify-fetch-get.ps1
```

11 项检查覆盖：不同 origin、浏览器请求确实到达 API 但 CORS 阻止读取、原生 suspend 请求返回 Promise/200、JsonObject 请求头与嵌套结果、Unicode、响应头、404、超时 rejection、非法 header 类型以及零激活。报告和截图位于 `webview2-sample/build/evidence/fetch-get.json` / `fetch-get.png`。

先运行本脚本，再运行原有后台回归；最后运行关闭画面脚本退出应用。页面 origin 随端口动态注册，不对任意网页开放桥接。HTTP 客户端使用 JDK 21 sendAsync；协程取消时取消请求 future，应用退出时关闭客户端和 fixture 服务。参见 [JDK HttpClient](https://docs.oracle.com/en/java/javase/21/docs/api/java.net.http/java/net/http/HttpClient.html)。

主题验收：启动测试模式后运行 `scripts/verify-theme.ps1`，通过 `/control` 的 `colorScheme`（Auto/Light/Dark）更新 Compose 参数，检查媒体查询事件、页面保留和激活次数。独立原生共享 profile 验收使用 `:webview2-compose:themeSmoke`。

导航验收：`scripts/verify-navigation.ps1` 验证 Compose 页面状态与前进/返回。`/control` 支持 url/goBack/goForward/stopLoading/reload，`/state` 返回 url/title/isLoading/canGoBack/canGoForward。独立原生回调与 HTTP 导航验收：`:webview2-compose:navigationSmoke`。


实例与视口专项：`./gradlew.bat :webview2-compose:lifecycleSmoke :webview2-compose:viewportSmoke`。性能记录：`./scripts/measure-performance.ps1`，见 [性能基线](performance.md)。HTTP `/state` 同时提供 lifecycle、errorStage、viewportReady、请求/帧 revision、frameScale 和 JVM 累积分配字节；不返回测试 token。

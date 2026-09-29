# 0.1.0

- 支持在 Windows x64 的 Compose Desktop 中嵌入 WebView2，网页参与 Compose 绘制，可与普通 Popup、Dialog 和 DropdownMenu 叠加。
- 支持页面导航、鼠标与滚轮交互、JavaScript 执行、启动脚本和 JSON 变量注入。
- 支持通过 `@JavascriptInterface` 同步调用 Kotlin 方法，以及通过显式消息请求／回复实现异步交互。
- 提供无需创建窗口的 WebView2 Runtime 版本查询；sample 在未找到 Runtime 时显示居中提示。
- 提供浏览历史、导航拦截、新窗口处理、动态主题、UA 和浏览器行为配置。
- 提供实例生命周期、`awaitClosed()` 和结构化错误；关闭时保留最后一帧，避免网页提前空白。
- 提供原生快捷键回调，以及尺寸、缩放和帧版本同步。
- 优化帧转换，实测动态页面 JVM 分配约降低 50%。
- 提供 Maven Local 与 Maven Central 发布流程，以及内置 Java 运行时的 Windows 绿色版 sample；仍需安装 WebView2 Runtime。

当前为实验版本；真实键盘／中文 IME、跨屏 DPI 和长时间运行仍待完整验收。

# 项目工作规范

- 功能目标以 `docs/SPEC.md` 为准，执行状态记录在 `docs/PLAN.md`，验收证据记录在 `docs/validation.md`。
- 保持标准 Compose Desktop/AWT；网页画面必须进入 Compose 绘制，不添加可见原生网页覆盖窗口，不切换全局 Skiko 后端，不为业务 Popup/Dialog 引入专用替代组件。
- 自动测试不得抢占用户设备焦点。使用 `:webview2-sample:run --args="--test"` 与 `scripts/verify-background.ps1`；禁止系统鼠标键盘注入、激活窗口或在自动测试中开启 native focus。
- 真实键盘、中文 IME、跨屏等需要前台交互的验收，未经用户安排不得运行；在报告里标记待验收，不用 JS 赋值冒充真实输入。
- WebView2/COM 调用仅在所属 STA 线程；异步回调使用弱引用；关闭之后不得操作失效会话。图像帧只保留最新帧，不建立无界队列。
- 修改后运行相应构建与集成检查；有图形/输入改动时运行后台验收。生成文件保留在 `build/`，不要提交 SDK 下载、浏览器 profile、截图 token 或编译产物。

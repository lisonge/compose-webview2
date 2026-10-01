# WebView 组件规范

本文件是本项目功能与验收的依据。目标是在标准 Compose Desktop/AWT 中提供类似 Android WebView 的独立组件，业务代码无需为浏览器改写 Popup、Dialog 或切换渲染后端。

## 必须满足

1. `WebView(state, modifier)` 仅在自身布局区域绘制；位置、尺寸、裁剪由 Compose 管理。
2. 网页内容经过捕获成为 Compose 绘制内容；不得以可见 HWND 覆盖网页区域，不得修改全局 Skiko 后端。
3. 普通 Compose 兄弟控件、Popup、DropdownMenu、Dialog 按正常层级叠放；不需要专用浏览器弹层。
4. 只有命中 WebView 且未被上层消费的输入可以发送到浏览器。弹层点击不能触发下面的网页。
5. 支持导航、加载状态、错误报告、鼠标和滚轮；键盘、中文 IME、焦点切换是最终验收的必要项目。
6. 初始化和销毁异步且有明确所有权；UI 线程不等待浏览器初始化；帧队列有界。
7. Runtime 缺失、捕获失败不得静默显示白屏。离开 composition 后释放控制器、捕获池和线程。
8. JavaScript 桥接：注解普通 fun 同步返回，Java 反射扫描；不支持注解 suspend 自动导出。异步采用显式消息 listener 与回复，业务管理协程及 JS Promise/callback；导航/关闭使旧回复失效。见 [JavaScript bridge](javascript-bridge.md)。

## 边界

首版 Windows x64、JDK 21、系统 WebView2 Runtime。CPU 帧传输可用于原型；不承诺零拷贝、DRM、完整浏览器无障碍或任意 3D 变换下的 IME 候选框定位。动画网页占用资源，但不得改变其他 Compose 区域的渲染机制。

## 验收

- V1 本地 HTML 的动态内容确实被捕获，网页帧可在 Compose 截图中出现。
- V2 普通 Compose 按钮覆盖网页，点击后网页点击计数不变。
- V3 标准 Popup、DropdownMenu、Dialog 可见且交互正常；关闭后浏览器恢复输入。
- V4 独立 Compose 输入框保持正常绘制与输入，不受网页更新影响。
- V5 网页输入框中文组合输入、候选位置、复制粘贴、Tab 进入/离开正常。
- V6 调整尺寸、圆角、透明度、重复创建销毁、最小化恢复不崩溃；跨 DPI 正确。
- V7 记录实际帧率、CPU 拷贝量及资源趋势，不以成功编译替代效果验收。

任何未完成或未验证项必须单独标注，不能报告为全部完成。

## 自动化测试不得抢占本机焦点

用户同时在设备上工作。自动化使用 sample 内的本地 HTTP API、Compose 内部事件投递与截图，不得使用系统鼠标键盘注入、激活窗口或主动请求系统焦点。测试窗口放在屏幕外且禁止自动聚焦；浏览器测试模式禁用 MoveFocus。真实 IME/系统焦点验收需另行安排，不能用 JS 修改输入框冒充已验收。


## 网页主题

WebViewSettings.preferredColorScheme 支持 Auto/Light/Dark，默认 Auto。初始化在首次导航前应用，变更在所属 STA 更新 profile，不重建页面。共享 profile 的实例共享主题，以最后设置为准；不承诺强制变换网站配色。


## 浏览器行为配置

创建 WebView2 环境时固定使用 FluentOverlay 滚动条，覆盖网页内容而不占用布局宽度；无需修改 Edge 设置。网页自定义滚动条 CSS 仍可能影响最终效果。

WebViewSettings 增加 userAgent、javaScriptEnabled、zoomFactor、userZoomEnabled、devToolsEnabled、contextMenuEnabled、statusBarEnabled。默认分别为 null/true/1.0/false/false/false/false。初始设置在首次业务导航前发送，更新在 STA 执行，不重建页面；UA null 恢复创建时原始 UA，zoomFactor 即时应用，脚本/UI 开关下次导航生效。输入拒绝非法缩放和空白或包含 CR/LF/NUL 的自定义 UA。浏览器原生菜单不承诺 Compose 弹层行为。


## 导航与客户端事件

WebViewState 提供 goBack/goForward/stopLoading 和实际页面 reload，暴露 url/title/isLoading/canGoBack/canGoForward。WebViewClient 提供顶层导航同步允许/取消以及新窗口 Ignore/OpenInCurrentWebView；默认不弹出原生窗口。client 更新不重建会话。回调在 STA 同步执行，不等待 UI；异常取消请求并报告 status。通过 JDK Function 注册原生回调，无新增反射 keep 规则。关闭实例后不再分派新请求。


## 嵌入稳定性改进

- 实例生命周期采用 Detached/Initializing/Active/Closing/Closed。关闭请求与完成分开；awaitClosed 等待调用时的实例，由原生 STA 清理完成信号驱动，不依赖全局计数。等待取消不影响清理，新实例不延长旧等待。窗口关闭前保留最后一帧。
- WebView 不绘制内置加载/错误文字。WebViewError 提供 Initialization/Navigation/Rendering/BrowserProcess/Callback/Operation/Closing 分类及可用的原始错误数据。主动导航取消不产生加载错误，新导航清理旧导航错误；其他错误保留到重建或被后续错误替换。不承诺自动恢复浏览器/GPU 崩溃。
- WebViewClient.onAcceleratorKey 使用原生 AcceleratorKeyPressed，返回是否消费；仅快捷键范围，包含 Windows 虚拟键码、按下/抬起、重复与修饰键，不代替文字或 IME 事件。回调在 STA 立即返回，界面工作由业务分派。
- 请求视口带显式 revision；交付帧头原子携带尺寸、scale、revision。resize 后确认浏览器布局尺寸/DPR，再获取新捕获帧；Compose 拒绝过时版本。isViewportReady 表示匹配帧已交付，不表示页面所有异步绘制完成。静态页面也必须恢复画面。
- 维持最新帧有界缓存；记录静态、动画、脚本滚动和 resize 的交付帧率、JVM CPU/分配与视口恢复时间。性能数据说明测量边界，真实跨屏 DPI 与输入仍需人工验收。

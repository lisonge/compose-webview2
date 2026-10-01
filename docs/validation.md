# 验收记录

## FluentOverlay 滚动条（2026-10-01）

- 在所属 STA 创建环境选项，通过 ICoreWebView2EnvironmentOptions8 设置 FLUENT_OVERLAY；未添加浏览器 flags、修改 Edge 或系统设置、注入滚动条 CSS。
- validation.html 的滚动占位高度从 600px 改为 150vh。实测 scrollHeight=1754、innerHeight=517，innerWidth=clientWidth=788（CSS px）；后台滚轮事件滚动后占宽仍为 0。
- :webview2-compose:check、:webview2-sample:classes、:webview2-compose:browserSmoke 通过。sample 使用 run --args="--test"；verify-background.ps1 共 24 项通过，activations=0。首次 HTTP 请求受本机代理影响返回 503，在测试进程设置 NO_PROXY=127.0.0.1,localhost 后重跑通过。
- 已查看 scrollbar-scrolled.png，右侧细滚动条可见，网页画面仍由 Compose 绘制。证据：build/fluent-overlay-background.log、webview2-sample/build/evidence/scrollbar.json、scrollbar-scrolled.png、report.json。
- 未验证闲置自动隐藏时间、真实鼠标拖动、键盘/IME 或跨屏 DPI；不将后台滚轮验收替代前台输入验收。

## ProGuard 发布产物验收（2026-09-29）

- 随库打包 `META-INF/proguard/webview2-compose.pro`；sample 从实际 JAR 提取规则，启用 ProGuard 7.10.0 的 shrink / optimize / obfuscate。
- `:webview2-sample:proguardReleaseJars` 构建通过；`scripts/verify-release-artifacts.ps1` 确认普通库类确实改名、JS 方法名/JNI 回调保持、桥接 Kotlin Metadata 和注解仍在、DLL 和 consumer rules 保留。
- `:webview2-sample:runReleaseBackground` 仅加载处理后的 JAR。fetchGet 11 项、Compose 后台 21 项、关闭时帧像素一致检查全部通过，窗口激活次数 0。
- 修复了仅在混淆产物暴露的 Ktor volatile 字段反射问题，并保留 Compose StabilityInferred 注解，避免桥接类元数据被 ProGuard 丢弃。
- 未全局忽略告警；依赖与 Compose 生成函数仍有非致命 Kotlin 元数据校验告警，详见 [shrinking](shrinking.md)。本次验证的是 JVM ProGuard 产物，不是 Android R8 APK。

依据 [SPEC.md](SPEC.md)。以下区分已实测、实现但未验收、未实现。不能将编译通过或程序化输入等同于真实 IME 验收。

## JavaScript 桥接验收（2026-09-29）

- `:webview2-compose:check :webview2-compose:browserSmoke :webview2-compose:bridgeSmoke :webview2-sample:classes` 全部通过。
- 真实隐藏 WebView2 的首个页面脚本可访问接口、变量和初始化脚本结果。普通方法返回非 Promise；180ms 同步方法阻塞网页计时器；suspend 方法返回 Promise，挂起期间网页计时器继续。
- 字符串（含引号、换行、中文）、整数、nullable、JSON 对象和 Unit 返回通过；参数类型错误、同步异常、异步异常转换通过；未注解方法不导出，并发异步调用结果正确。
- 导航后重新注入并取消旧协程；多实例隔离、关闭取消、非许可来源直接调用底层代理被拒绝；最终 activeNativeHosts 为 0。
- Compose 后台验收 21 项通过（原 18 项加 3 项桥接检查），窗口激活次数 0。关闭帧比较通过，原生资源归零后画面仍像素一致。截图及报告在 `webview2-sample/build/evidence/`。
- 退出时仍可见此前记录的 Chromium `Chrome_WidgetWin_0 / 1411` 日志，本次未解决该 Runtime 清理日志；构建与正常关闭成功。真实键盘/IME 等原有待验收项目不变。

## 2026-09-29 本机结果

环境：Windows Build 26200、JDK 21、MSVC 19.41、Windows SDK 10.0.22621.0，系统 WebView2 Runtime。固定 SDK 1.0.3650.58。

构建命令：`gradlew.bat :webview2-compose:check :webview2-compose:browserSmoke :webview2-sample:classes`。

真实浏览器 smoke 通过：

```text
CAPTURE OK: 320x240, known page color matched
MOUSE OK: browser DOM click handler executed without focus
TEARDOWN OK: no live native hosts
```

后台示例通过 `scripts/verify-background.ps1` 的 18 项断言：

- 捕获尺寸与 Compose 物理像素布局相符，真实 HTML 已加载。
- 普通 Compose 覆盖按钮能点击，网页点击计数不变。
- 标准 Popup、Dialog、DropdownMenu 能显示、交互和关闭；点击没有进入网页。
- 弹层关闭后网页按钮恢复响应，滚轮改变网页 scrollY。
- 独立 Compose 输入框接收应用内部投递的文字。
- 调整窗口尺寸后捕获尺寸跟随变化。
- 5 次销毁重建，每次关闭后原生宿主数回到 0，重建后回到 1。
- 动画持续产生新帧，自动化窗口激活次数为 0。

已查看 Compose 截图，确认网页、圆角、普通覆盖按钮和 Popup/Dialog 的可见叠放。PNG 与原始报告在 `webview2-sample/build/evidence/`，可重新运行脚本生成；它们是生成文件，不提交到 Git。

一次 3 秒短采样：网页区域 1576×1154，约 54.2 FPS，Java 进程 CPU 时间相当于约 0.82 个 CPU 核，Java heap 约 72 MB。CPU 指标不包含外部 WebView2 进程，不是端到端延迟、GPU 占用或长期内存稳定性结论。CPU readback/copy/upload 开销明显，不能称为零开销或生产性能已达标。

## 待前台验收：不在用户工作期间自动运行

交互模式命令：`gradlew.bat :webview2-sample:run`。

1. 点击网页输入框，用微软拼音输入中文，检查组合文字、候选窗位置和提交结果。
2. 在 Compose 输入框、网页输入框、Popup 和 Dialog 间切换；确认输入进入正确目标，弹层关闭后焦点可恢复。
3. Tab / Shift+Tab 进入网页、遍历页面元素并离开；测试复制粘贴、快捷键、选择拖动。
4. 拖动主窗口、跨不同 DPI 显示器、最小化恢复，检查清晰度、点击坐标和候选框位置。
5. 长时间播放动画、反复切换路由并观察 Java/native/WebView2 进程资源；补充 1080p/4K、GPU 与输入延迟测量。

这些项目暂停是为了遵守用户不抢焦点的要求。当前只实现了 `MoveFocus` / `MoveFocusRequested` 的基础桥接，不能提前保证完整键盘/IME 行为。

## 其他未验收或未实现范围

- 无 Runtime、浏览器进程崩溃的错误显示路径已有实现，尚未通过破坏环境的方式测试。
- 设备丢失后自动恢复、浏览器进程自动重建尚未实现。
- 无障碍树、拖放、触摸/笔输入、浏览器原生弹出 UI 的完整行为尚未实现或验收。
- 页面右键菜单、select 弹出列表、新窗口、权限请求、下载等不属于本次核心叠放验收。
- DRM 受捕获路线限制，不保证可用。

CI 只验证编译/JNI，不将无交互云 runner 的结果当作本机图形与输入验收。

## 2026-09-29 退出日志修复

用户报告手动关闭窗口后出现 `Failed to unregister class Chrome_WidgetWin_0. Error = 1411`，网页运行正常。旧版通过后台 HTTP `/quit` 复现相同日志，Gradle 仍正常退出。

发现 sample 直接调用 `exitApplication()`，而 WebView 的 `onDispose` 仅发出异步关闭请求，父 HWND/JVM 退出没有等待 STA 清理。sample 现改为先移除 WebView、关闭弹层，在不阻塞 EDT 的情况下等待状态为 Closed 且原生宿主数为 0，再调用 `exitApplication()`。等待超过 5 秒会保留父窗口并显示可重试的错误，不悄悄强制退出。

修复后的后台关闭记录为 `[compose-webview2] Native teardown complete before window disposal; live hosts=0`，Gradle 成功退出且未再次出现 1411。测试窗口激活次数为 0。该结果证明此次复现的退出时序已改善，不保证所有 WebView2 Runtime 内部日志都由同一原因产生。

## 2026-09-29 关闭动画前网页空白修复

上述第一版退出修复提前从 composition 移除了 WebView，使原生清理期间网页区域空白。现改为 `WebView(running = false)` 停止浏览器，但保持组件与最后一帧直到父窗口关闭；关闭时也不主动移除弹层。原生清理仍先于父窗口销毁，不退回原先的退出竞态。

新增 `scripts/verify-close-frame.ps1`：通过测试专用 `/quit?hold=true`，将正常关闭流程停留在“宿主已释放，父窗口尚未关闭”的检查点；暂停测试页动画后，对关闭前后整个 WebView 区域的原始像素做 SHA-256 对比。实测像素完全一致，活跃原生宿主为 0，窗口激活次数为 0；随后释放检查点正常退出。18 项后台回归同样通过。系统桌面的实际关闭动画未自动操作，以遵守不抢焦点约束。

补充：本轮经历 5 次销毁重建后退出时，Runtime 的 1411 日志再次出现，尽管关闭前宿主计数已为 0。因此前述两次未复现只能证明退出顺序已修正，不能证明 Runtime 注销窗口类日志被彻底消除。此日志仍需独立跟踪；没有屏蔽日志或通过提前退出绕过清理。

## 模块命名调整（2026-09-29）

Gradle group 改为 li.songe.webview2；目录改为 webview2-compose、webview2-native、webview2-sample，同步构建脚本、CI 和文档。Kotlin 包名与 JNI 符号保持兼容。执行新模块的 check、browserSmoke 和 sample classes 全部成功；确认 JNI、D3D11、320×240 捕获、无焦点 DOM 点击及原生资源释放。旧 CMake 缓存保留在 webview2-native/build/windows-x64-before-module-rename，新目录已重新生成。

## 原生 fetchGet 示例验收（2026-09-29）

sample 新增 `suspend fetchGet(url: String, options: JsonObject): JsonObject`，用 JDK HttpClient 发起异步 GET。页面和 API 分别从两个不同的 loopback 端口提供；API 故意不发送 CORS 许可头。

- `:webview2-sample:classes` 构建通过。
- `scripts/verify-fetch-get.ps1` 的 11 项检查全部通过。服务端确认浏览器请求到达，但网页 fetch 因 CORS 无法读取；原生桥接请求返回 200，嵌套 JSON、请求/响应头和 Unicode 完整。404、超时、非法 header 类型行为符合约定。
- 原有 `scripts/verify-background.ps1` 21 项通过；`scripts/verify-close-frame.ps1` 关闭帧像素一致测试通过。窗口激活次数均为 0。
- 已查看 `webview2-sample/build/evidence/fetch-get.png`；机器报告为同目录 `fetch-get.json`。没有依赖外网服务，也没有更改浏览器 CORS 配置。
- 请求是原生客户端请求，不共享网页 cookies。样例仅处理文本/JSON，响应缓存在内存中，不是流式下载接口。


## 显式 native 回调注册与混淆验收（2026-09-29）

- native 接收回调 Map，仅查找 JDK Map.get、Function.apply、LongConsumer.accept、Runnable.run；不再查找 JavascriptBridgeRuntime 的类或方法名。回调值校验接口类型，构造失败会释放已创建的全局引用。
- 删除 JavascriptBridgeRuntime keep；NativeBridge 仅保留存活的 native 方法及类名。业务注解反射和 JNA 的规则仍保留。
- check、bridgeSmoke、sample classes 通过，包括缺失/错误类型回调拒绝、同步/异步、导航与关闭取消、实例隔离和宿主归零。
- ProGuard release 构建与产物检查通过。mapping 确认 JavascriptBridgeRuntime 重命名为 li.songe.compose.webview2.internal.c，未配置 runtime keep；Kotlin 元数据和业务导出注解仍在。
- 仅使用混淆后的 JAR 启动 sample：fetchGet 11 项、后台 21 项和关闭帧像素一致检查全部通过。激活次数 0，关闭时原生宿主 0。
- 本轮日志：webview2-sample/build/release-map-run.log；证据：webview2-sample/build/callback-map-evidence/。未进行前台键盘/IME 测试，也未宣称完成 R8 运行验证。


## JAWT 与 keep 简化验收（2026-09-29）

- 删除 JNA 依赖及整包 keep；现有 DLL 链接 JDK 的 JAWT，读取 AWT Window 的 HWND 后立即释放 surface info、解锁并释放 surface，不经 JAWT 绘图或改变焦点。
- 注解规则改为 keepclassmembers，不再保留业务类名及 descriptor classes。保留现有注解 API、Kotlin 反射元数据规则与 JNI 入口名称规则，未引入 KSP。
- check、bridgeSmoke、sample classes、ProGuard release 构建全部通过。静态产物检查确认 SampleJavascriptApi 混淆为 li.songe.compose.webview2.sample.e，导出方法名、Kotlin Metadata 和注解仍在，处理后 JAR 中没有 JNA 依赖。
- 实际运行混淆后的 sample：原生网络 11 项、后台 21 项、关闭帧像素一致检查全部通过；包含 JAWT 获取句柄、普通/suspend 导出、JSON、Popup/Dialog、resize、5 次销毁重建。窗口激活次数 0，关闭时原生宿主 0。
- 日志：webview2-sample/build/release-jawt-run.log；证据：webview2-sample/build/jawt-evidence/。ProGuard 仍存在之前记录的依赖元数据警告，未全局屏蔽。未执行前台键盘/IME、跨屏测试或 R8 验证。


## 进一步收窄反射规则（2026-09-29）

- 删除 JavascriptInterface 类型的显式 keep，依靠直接类引用保留类型，允许其名称混淆。
- kotlin.Metadata 使用 ProGuard 文档要求的类规则，去掉全部成员保留。属性规则由 *Annotation* 收窄为 RuntimeVisibleAnnotations、AnnotationDefault，并保留 Signature、InnerClasses、EnclosingMethod。
- StabilityInferred 仅保留类型和 int parameters()，允许混淆；不再使用 { *; }。曾试验只保留类型，实际导致 SampleJavascriptApi 元数据出现 dangling annotation method reference、未适配 JSON 类型重命名，fetchGet 未导出，因此恢复的仅是这个具体成员。
- 产物检查增加导出注解/Compose 注解实际混淆、业务元数据适配重命名的断言，防止仅检查注解存在而漏掉元数据失效。
- 最终 ProGuard 构建、产物检查通过；混淆后的 fetchGet 11 项、后台 21 项和关闭帧检查全部通过，激活次数 0。证据为 webview2-sample/build/narrow-rules-evidence/，运行日志为 webview2-sample/build/release-narrow-rules.log。
- 最终为 5 条规则，无全成员或注解属性通配保留。class * 只用于匹配任意包里的带指定注解的方法，不保留这些类的全部成员。没有宣称这是任意工具链的理论最小规则；原有依赖元数据警告和前台验收限制仍适用。


## 两条规则与显式消息桥接（2026-09-29，覆盖此前 suspend 自动导出约定）

- 消费者规则仅保留注解方法与 NativeBridge native 名称两条；删除全部 keepattributes、Metadata 和 StabilityInferred 规则，没有迁移到 sample 规则中。
- 注解方法扫描改为 Java Method，去掉库对 kotlin-reflect 的直接依赖。注解 suspend 在注册时被拒绝；类型与范围校验保留，引用类型 null 按 Java 语义处理，不再读取 Kotlin 可空性。
- 新增 addWebMessageListener 与单次 WebMessageReply。业务自己启动协程，页面通过 postMessage/onmessage 和请求 ID 实现 Promise/callback；导航/关闭拒绝旧回复，但不自动取消业务作用域。sample 实现错误返回、并发请求、35 秒兜底超时、关闭时取消自身作用域，网络协程仍为私有实现。
- check、sample classes、bridgeSmoke 和 ProGuard 构建通过。bridgeSmoke 检查同步阻塞、类型校验、显式异步消息、拒绝 suspend、并发、导航及关闭后的旧回复拒绝、实例隔离及宿主归零。
- 静态产物检查确认库 JAR 只包含两条规则，注解方法名保留，业务类/注解可混淆。有效应用配置仍含 Compose 默认提供的 RuntimeVisibleAnnotations,AnnotationDefault；因此结论只适用于当前默认配置，不能声称删除运行时注解后扫描仍有效。
- 仅用混淆后的 JAR 运行：fetchGet 11 项、窗口后台 21 项与关闭帧像素一致检查全部通过，激活次数 0。证据为 webview2-sample/build/two-rules-evidence/，日志为 webview2-sample/build/release-two-rules.log。
- README 和 JavaScript bridge / shrinking 文档已迁移至新 API。前台键盘/IME/跨屏测试仍未执行；没有 R8 运行验证。


## Maven Local 发布验收（2026-09-29）

- 库启用 maven-publish，发布 java component 与 sources JAR，默认坐标 li.songe.webview2:webview2-compose:0.1.0-SNAPSHOT；releaseVersion 可覆盖版本。
- check 和 publishToMavenLocal 通过；POM 包含 Compose、serialization、stdlib 及 foundation 依赖，主 JAR 包含 Windows DLL 和两条 keep 规则。
- 使用 webview2Version=0.1.0-SNAPSHOT 运行 sample classes 与 verifyLocalPublication，通过 ModuleComponentIdentifier 断言确认外部依赖来源，实际 JAR 位于 C:/Users/lisonge/.m2/repository/li/songe/webview2/webview2-compose/0.1.0-SNAPSHOT/。
- 发布后的 classpath 执行 JNI 探测，输出 JNI OK、D3D11 hardware OK、Graphics Capture supported；未创建前台窗口。默认源码依赖模式的 keep 规则提取同样验证通过。
- 本轮只发布到本机 Maven Local，未配置或执行远程发布。


## 独立 Runtime 查询（2026-09-29）

新增 WebView2Runtime.getAvailableVersion()，通过官方 GetAvailableCoreWebView2BrowserVersionString 查询默认可用浏览器版本，无需 Compose、AWT 窗口或浏览器环境。ERROR_FILE_NOT_FOUND 返回 null，其他 HRESULT 抛含错误码的 IllegalStateException，DLL 加载失败不伪装成未安装。返回字符串可能含 Edge 预览渠道，不能作为所有安装版本的清单。

check、本地重新发布及 verifyLocalPublication 通过，查询本机结果为 154.0.4258.37；查询前后活跃原生宿主计数一致，未创建前台窗口。本机未卸载 Runtime，未在真实未安装设备上验证 null 分支。


## Windows 绿色版与 Release 工作流（2026-09-29）

新增 release.yml，Windows 2022 + JDK21 构建库与混淆后的 app image，打 ZIP，校验 JAR，上传 workflow artifact；v* 标签在构建成功后发布 GitHub Release。手动运行仅产出 artifact。stage-release.ps1 整理库 JAR、sources/POM/module、绿色版 ZIP 和 SHA-256。

本机 packagePortable 构建成功。直接启动产物 WebView2Sample.exe --test（隐藏窗口，沿用不激活测试机制），fetchGet 11 项、窗口 21 项、关闭帧一致检查全部通过，激活次数 0，进程正常退出。ZIP 为 76,654,223 字节，产物在 build/release/0.1.0-SNAPSHOT/。YAML 语法解析通过，库 POM/module 与附件整理通过；未推送标签或触发远程 workflow。测试证据在 webview2-sample/build/portable-evidence/，日志为 portable-run.log / portable-error.log。


## sample 缺少 Runtime 提示（2026-09-29）

启动时查询 Runtime，未找到则不创建 WebView，在原网页区域居中显示“未检测到已安装的WebView2”。关闭流程在没有 WebView 时不等待不存在的 Closed 状态。检测错误仍抛出，不误报未安装。

sample classes 通过。使用仅作用于测试进程的 WEBVIEW2_BROWSER_EXECUTABLE_FOLDER 指向不存在目录模拟 Loader 未找到；后台截图 missing-runtime.png 确认文字居中，frames=0、activeNativeHosts=0、activations=0，通过 HTTP 正常关闭。未卸载或修改本机 Runtime。


## 发布工作流更新（2026-09-29）

查询官方 latest release 后升级大版本：actions/checkout v7（v7.0.1）、actions/setup-java v6（v6.0.1）、gradle/actions/setup-gradle v6（v6.4.0）、actions/upload-artifact v7（v7.0.1）。合并为单个 release job，移除 download-artifact；标签发布直接使用本 job 的文件，手动执行仍仅上传 artifact。GitHub Release 正文使用 CHANGELOG.md，该文件只包含当前 0.1.0 发布说明。YAML 解析与结构检查通过，未触发远程发布。


## 对齐 priv-kit 发布流程（2026-09-29）

release.yml 改为仅由 v* 标签触发；单个 Windows job 构建及整理附件，使用 softprops/action-gh-release@v3 创建或更新 Release，正文读取 CHANGELOG.md，附件缺失时报错。删除手动触发、workflow artifact 上传和 gh CLI 发布分支，保留预发布标签识别与最新版 actions 大版本。Maven Central 尚未配置，不复制参考项目的凭据及发布任务。YAML 解析和触发器、job、发布步骤结构检查通过；未推送标签或执行远程发布。


## Maven Central 发布接入（2026-09-29）

使用 Vanniktech 0.37.0 base 插件保留原 mavenJava publication，增加 Central 发布与条件签名。工作流沿用 priv-kit 五个 Secrets，以环境变量传递凭据，临时密钥环在 finally 删除；先构建校验，再发布 Central 与 GitHub Release。POM 项目和 SCM 链接来自 CI 仓库，补齐开发者、用户确认的 Apache-2.0 和包含使用文档的 javadoc JAR。

POM、文档 JAR、module 元数据生成及 publishToMavenLocal 成功；Central 发布 --dry-run 成功，任务图包含 signMavenJavaPublication 与发布任务。YAML 结构检查通过。未使用真实凭据执行签名或远程发布，未启动 GUI。


## 直接采用 priv-kit 发布流程（2026-09-29）

改用完整 com.vanniktech.maven.publish 插件，自动配置 maven publication、sources 和 javadoc；移除自定义文档 JAR。POM 项目和 SCM URL 固定为 https://github.com/lisonge/compose-webview2。工作流依次写入 Secrets 配置、发布 Maven Central、构建 sample 绿色版、使用 CHANGELOG.md 创建 GitHub Release 并上传 ZIP。保留 Windows runner 和最新 actions 大版本；Bash 中用 pwd -W 生成 Gradle 可识别的 Windows 密钥环路径。

publishToMavenLocal 成功；Central 签名发布任务 dry-run 成功；YAML 与实际 POM 地址检查通过。未执行真实签名、远程发布或启动 GUI。


## sample 标准构建配置（2026-09-29）

移除 sample 自定义 Gradle 任务及混淆配置，删除失效的产物整理/混淆校验脚本和 sample ProGuard 文件。库的两条 consumer rules 保留。createDistributable 构建成功；run --args="--test" dry-run 通过；发布 YAML 解析通过。之前的混淆和本地依赖切换记录仅作为历史验收，不代表当前存在对应任务。

直接运行标准产物 WebView2Sample.exe --test（隐藏且不激活），fetchGet 11 项、Compose 后台 21 项及关闭帧检查全部通过，activations=0。


## 库构建配置精简（2026-09-29）

生成资源目录通过 builtBy 携带 buildNative 依赖，删除 processResources/sourcesJar 显式依赖；三个 smoke 任务合并注册，classpath 自动携带编译依赖。删除与插件默认值相同的 coordinates 配置。check（含 JNI/D3D11/capture 探测）、publishToMavenLocal 通过，POM 坐标不变，browserSmoke/bridgeSmoke dry-run 确认编译和原生构建任务依赖完整。没有新增 Gradle 脚本。


## 显式 API 与构建输入（2026-09-29）

启用库 explicitApi，补齐 public 声明和 WebViewState 属性类型；原生诊断 main 标记 internal。移除 check 对 nativeSmoke 的依赖。buildNative 新增 JDK 安装路径、include、release 和 lib/jawt.lib 输入。check、sample classes、publishToMavenLocal 通过，构建日志确认未执行 nativeSmoke；本轮增加输入后 buildNative 重新执行。

## SDK 版本声明精简（2026-09-29）

WebView2Sdk.cmake 提取版本和 SHA-256 变量，下载 URL 由版本拼接。版本及校验值未改变，buildNative 配置、编译和安装通过。

## 发布配置不改写源码文件（2026-09-29）

参考 morph-compose，Secrets 通过步骤环境变量和 Gradle -P 参数传递，删除对 gradle.properties 的追加写入。版本解析后同时传给检查/打包和发布步骤；先检查并构建，再发布 Central。secring.gpg 使用现有 *.gpg 忽略规则。YAML 和步骤结构检查通过，未读取真实密钥或触发远程发布。

## 固定项目版本与标签校验（2026-09-29）

根项目版本设为 0.1.0，删除发布工作流中的版本解析和 -PreleaseVersion 参数。verifyReleaseVersion 在匹配 v0.1.0 时通过，v0.1.1 时按预期拒绝；生成 POM 版本为 0.1.0。YAML 解析通过，未执行远程发布。

## 本地发布快照版本（2026-09-29）

publishToMavenLocal 成功，检查本地仓库 POM 和 module 版本均为 0.1.0-SNAPSHOT。另一次调用 verifyReleaseVersion（v0.1.0）及生成 POM 成功，正式版本仍为 0.1.0。文档已更新本地依赖坐标；未发布远程。

## 按运行环境选择版本（2026-09-29）

移除按 Maven Local 任务名判断的逻辑。普通本地调用生成 POM 为 0.1.0-SNAPSHOT；仅在验证进程设置 GITHUB_ACTIONS=true、GITHUB_REF_NAME=v0.1.0 后，生成 POM 为 0.1.0 且标签校验通过。未执行远程发布。

GITHUB_ACTIONS 改用 isPresent；测试进程设置值为 false 时仍使用正式版本，verifyReleaseVersion 通过，确认不再检查值。

## 通用 CI 版本判断（2026-09-29）

改用 CI 环境变量的 isPresent。测试进程移除 CI 后 POM 为 0.1.0-SNAPSHOT；设置 CI=false 后 POM 为 0.1.0 且标签校验通过，证明只检查存在性。未执行远程发布。

## allowOrigin 可变参数（2026-09-29）

改为 vararg origins: String，各来源仍通过原有 normalizeBridgeOrigin 校验并加入集合。中英文 README 已补充多来源示例。该修改改变 JVM 方法签名，已有编译产物需要重新编译。

## WebView2 主题（2026-09-29）

新增 WebViewColorScheme Auto/Light/Dark 和 WebViewSettings.preferredColorScheme。创建时传入初值，在首次导航前通过 ICoreWebView2_13/Profile 应用；动态更新投递到所属 STA，未初始化时保存最新值。Compose 主题不作为 DisposableEffect 重建键。接口不支持或调用失败会通过现有 status 错误通道报告。

check、sample classes、themeSmoke、bridgeSmoke 通过。themeSmoke 验证首屏深色 CSS、动态媒体查询事件、不重载、共享 profile 同步、不同目录隔离和宿主归零。HTTP verify-theme 验证 Compose Light/Dark/Light/Auto 参数更新及页面标记保留；fetchGet 11 项、后台 21 项、关闭帧一致性全部通过，activations=0。Auto 设置接受且页面保留；未改变用户系统主题验证 OS 实时切换。主题测试脚本初版字符串拼接错误已修复并重跑通过。

## 七项浏览器配置（2026-09-29）

新增 UA、页面脚本、整页缩放、用户缩放、DevTools、右键菜单和状态栏设置。初值在业务导航前排队，初始化未完成时缓存；修改经 JNI 在 STA 应用，不触发 Compose 重建。UA 保存原值供 null 恢复，仅在值变化时调用原生 setter，避免无意覆盖默认 UA/Client Hints；缩放仅参数变化时应用，避免其他配置更新重置用户缩放。

check、sample classes、settingsSmoke/themeSmoke/bridgeSmoke 通过。settingsSmoke 验证无效输入拒绝、首屏 UA/禁用脚本、缩放实时变化且页面标记保留、导航后恢复脚本及默认 UA。HTTP 主题、fetchGet 11 项、Compose 后台 21 项及关闭帧回归通过，activations=0。最后 UA setter 优化后重跑 settingsSmoke 通过。原生菜单、状态栏视觉效果、用户快捷键缩放及 DevTools 前台操作仍待人工验收。

userZoomEnabled 与 contextMenuEnabled 默认值改为 false，原生 Settings 同步；check 和 sample classes 通过。


## 导航、页面状态与客户端回调（2026-09-29）

新增 WebViewState goBack/goForward/stopLoading、当前页面 reload 及 url/title/isLoading/canGoBack/canGoForward。原生事件更新线程安全快照，Compose 在帧循环读取；导航完成按 NavigationId 过滤。新增 WebViewClient，导航同步允许/取消，新窗口默认 Ignore 或当前页打开，外部操作由应用自行分派。JNI 仅绑定 JDK Function，无新增 ProGuard 规则。

check 和 sample classes 通过。navigationSmoke 使用回环 HTTP 页面验证历史、标题变更、pushState URL、导航/重定向拦截、停止慢响应、当前页面 reload、新窗口忽略/当前页打开、client 动态替换、回调异常取消及宿主归零。发现并修复回调初始化捕获旧 client 的问题后重跑通过。bridgeSmoke/settingsSmoke/themeSmoke 回归通过。

HTTP verify-navigation 验证 Compose 状态与返回/前进方法；主题、fetchGet 11 项、Compose 后台 21 项及关闭帧回归通过，activations=0。没有触发系统浏览器或前台输入。真实键盘/IME 等原有人工验收项目仍待安排。


## 嵌入稳定性、视口与性能（2026-09-29）

实现实例级 lifecycle/awaitClosed、结构化 WebViewError、原生 accelerator 回调、显式视口 revision/scale 帧元数据。移除库内状态文字，sample 按实例等待清理并保留最后一帧。帧转换移除额外 JVM 像素数组，数据仍为不可变快照。

- check、sample classes，以及 browserSmoke/bridgeSmoke/themeSmoke/settingsSmoke/navigationSmoke 全部通过。
- lifecycleSmoke：两个实例隔离关闭、重组前开始等待、重复关闭、取消等待不取消清理、重建不延长旧等待、初始化期间关闭、host 归零通过。快捷键验证仅覆盖协议、消费、重复、client 替换与销毁，不使用真实键盘注入。
- viewportSmoke：静态页面 1×/1.5×/2×、同尺寸不同 scale、连续 20 次 resize 最终版本、失败导航 URL/错误码、导航恢复清错、拦截无错误、回调异常分类通过。最初发现静态页面确认后没有新帧，已通过确认后重启捕获取得快照修复，重跑通过。
- HTTP 后台 21 项、fetchGet 11 项、主题、导航通过。普通 Popup/Dialog/DropdownMenu 及 Compose 覆盖层正常，五轮销毁重建回到单 host，所有测试 activations=0。
- verify-close-frame：原生 host 归零后，网页区域像素与关闭前一致；实例等待完成后才销毁父窗口。Dialog 截图人工检查层级正常。
- 性能测量见 [performance.md](performance.md)：相同视口动画/滚动 JVM 分配约减少一半，交付约 55 FPS。未将 CPU 短测波动或脚本滚动作为输入验收。

证据日志：build/improvements-regression.log、build/improvements-final.log、build/improvements-background.log、build/improvements-fetch.log、build/improvements-theme.log、build/improvements-navigation.log、build/improvements-close.log；截图与报告仍位于 webview2-sample/build/evidence。

人工验收待办：真实 Escape/F12/Ctrl 组合键及抬起消费语义、中文 IME、Tab/系统焦点、跨屏 DPI、最小化恢复。浏览器/GPU 故障注入、可访问性、拖放、长期内存曲线亦未验证；未运行抢焦点测试。

## 自定义链接 sample（2026-09-29）

- sample classes、库 check 通过；run --args=--test 启动，verify-background.ps1 原有 21 项全部通过，activations=0。
- 经测试 HTTP API 切换自定义链接页签，加载本地 fixture 得到真实网页帧；加载 http://127.0.0.1:1/unreachable 得到 Navigation 错误，再加载有效链接后 Ready 且错误清空。未向自定义网页注入 sample JS 桥接。
- 已查看错误截图，确认地址栏、WebView 和可选择的错误详情可见；失败时可能保留上个页面画面，以右侧错误状态为准。截图保留在 webview2-sample/build/evidence/custom-error.png 和 custom-success.png。
- 自定义页签打开时正常退出，两个实例等待结束后才销毁窗口，Gradle 成功退出。真实地址栏键盘/回车、网页 IME 未进行前台验收。

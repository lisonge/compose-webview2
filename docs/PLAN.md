# 执行计划

## FluentOverlay 滚动条（2026-10-01）

- [x] 原生环境创建时设置 FluentOverlay；测试 HTML 空白区域由 600px 改为 150vh，确保纵向溢出。
- [x] 构建、browserSmoke、后台 24 项检查及滚动截图验收；滚动前后布局占宽为 0，activations=0。

## 原计划

依据：[SPEC.md](SPEC.md)。按关卡推进，先验证架构再扩展 API。

1. [x] 固化规范、验收条件与中断条件。
2. [x] 接入固定版本 WebView2 SDK；原生 STA 线程创建 CompositionController。
3. [x] Windows.UI.Composition visual → Graphics Capture → 有界 BGRA 缓冲；验证动态帧。
4. [x] Kotlin 生命周期、状态与 Canvas 绘制；构建标准 Popup/Dialog 验收示例。
5. [ ] 鼠标、滚轮、焦点、键盘与中文 IME；检测覆盖层输入不穿透。
6. [ ] 编译、原生集成测试、实际画面与交互验收，记录证据和限制。
7. [x] JavaScript 桥接：普通 fun 同步返回，suspend fun 返回 Promise；初始化变量/脚本、异常转换、导航与关闭取消，以及无焦点真实浏览器验收。
8. [x] 随库提供 keep 规则；sample 启用 ProGuard 压缩、优化、混淆；只加载处理后的 JAR 完成无焦点桥接/网络/窗口回归，并加入 CI 产物检查。

9. [x] native 回调改为显式 Map + JDK 函数接口，删除 runtime keep、缩小 JNI keep；验证 runtime 实际混淆后的完整后台回归。

10. [x] 使用 JAWT 获取 HWND，移除 JNA 依赖及 keep；注解仅保留方法，允许业务类名混淆并移除 includedescriptorclasses，完成混淆产物后台回归。

11. [x] 反射规则进一步收窄：删除导出注解 keep、去掉全成员与注解属性通配保留；精确保留 StabilityInferred.parameters，验证两个注解混淆及实际桥接回归。

12. [x] 按新约定移除 suspend 自动导出与 kotlin-reflect，使用 Java 反射和显式消息回复；库内仅保留用户指定两条规则，sample 迁移并通过混淆后台验收。

13. [x] Maven Local 发布与版本参数；sample 支持切换到发布依赖，验证外部模块来源、DLL/规则打包和 JNI 加载。

14. [x] 增加独立于 Compose 的 WebView2 Runtime 可用版本查询，区分未找到与检测失败，通过本地发布产物验证。

15. [x] Windows release.yml 与绿色版 ZIP；本机直接运行内置 Java 的 EXE，完成无焦点 HTTP 回归及附件校验文件生成。

16. [x] 沿用 priv-kit Secrets 接入 Maven Central 签名发布，采用 Apache-2.0；验证本地发布及 Central 任务图。

## 中断条件

需更换窗口宿主、修改 Compose/Skiko、破坏标准弹层语义、无法建立可靠键盘/IME 链路，或当前环境缺乏必要交互验证能力时，保存已完成实现和复现步骤，明确指出未通过的验收项。普通编译错误、依赖下载问题先尝试修复，不作为立即中断理由。

## 当前状态

核心渲染与鼠标交互原型已实现，后台 18 项检查通过，测试激活次数为 0。鼠标、滚轮、普通弹层与输入隔离、独立 Compose 输入框、5 次销毁重建已验收。构建与真实浏览器捕获集成测试通过。

第 5 项的真实键盘、中文 IME、Tab/焦点切换仍待前台验收；第 6 项的跨屏 DPI、最小化恢复、长时间资源测试仍待完成。为遵守用户“不抢占设备焦点”的要求，在这些前台验收处暂停，不将其视为已通过。

下一步：用户方便时运行交互示例，按 `docs/validation.md` 的人工清单验证键盘和 IME；修复实测问题后，再决定是否优化 CPU 帧拷贝或研究 GPU 互操作。当前实现不需要更换窗口宿主或改写业务 Popup/Dialog。


## sample 构建精简

已删除本地依赖切换、发布产物验证、自定义后台任务、混淆配置及规则提取。sample 直接依赖 :webview2-compose；使用标准 createDistributable，Actions 压缩绿色版。后台启动改用 run --args="--test"，HTTP 验收脚本保留。

库模块构建配置已精简：合并测试任务注册、用资源目录依赖替代显式任务连线，并保留原生构建与发布信息。

已启用库 explicitApi 并补齐公开声明；check 与硬件 smoke 分离；buildNative 追踪 JDK 路径、JNI 头文件、release 和 jawt.lib。

已将 WebView2 SDK 版本与校验值集中声明，消除下载 URL 中重复的版本号，原生构建验证通过。

发布版本统一写在根 build.gradle.kts，移除 releaseVersion 属性；Actions 发布前校验 Git 标签与项目版本一致。

Maven Local 发布按完整任务名自动追加 -SNAPSHOT；普通构建和标签发布保留基础版本。

版本策略修正为按环境判断：GITHUB_ACTIONS=true 使用正式版本，其余环境统一使用 -SNAPSHOT，不再检查任务名。

GITHUB_ACTIONS 仅判断是否存在，不检查变量值。

版本环境判断统一为 CI 是否存在，不限定 GitHub Actions。

allowOrigin 已支持 vararg，可一次注册多个来源；单参数调用保持源码兼容。

已实现 preferredColorScheme、JNI/STA 动态更新、原生 themeSmoke 和 Compose HTTP 主题验收。

已接入七项浏览器配置，原生 settingsSmoke 验证 UA、脚本控制、动态缩放及还原。浏览器菜单/DevTools 前台交互待用户安排。

用户缩放与默认右键菜单默认关闭；Kotlin、原生默认值和文档保持一致。

已实现第一批导航 API、页面状态及 WebViewClient；原生 navigationSmoke 覆盖历史/拦截/新窗口/停止/回调生命周期。


## 嵌入稳定性三轮改进（2026-09-29）

1. [x] 实例生命周期与 awaitClosed；替换 sample 全局 host 计数等待；结构化错误与纯画面 WebView。
2. [x] 原生 AcceleratorKeyPressed → WebViewClient，支持消费、重复键、修饰键；协议与关闭回归完成。
3. [x] 显式视口 revision、原子帧元数据、静态/快速 resize 回归；性能基线与移除一份 JVM 像素数组。
4. [x] 中英文使用文档、后台测量脚本、验收证据。
5. [ ] 用户安排后验收真实 Escape/F12/Ctrl 组合键、中文 IME、跨屏 DPI、最小化恢复；浏览器/GPU 故障注入及长期内存趋势待验证。

未引入可见原生覆盖窗口、专用业务弹层或额外 keep 规则。实际键盘输入继续按原中断条件留待人工验收，不用协议测试替代。

## 自定义链接 sample（2026-09-29）

已新增独立页签、URL 输入及加载/刷新/停止操作，展示页面状态和结构化错误。关闭等待涵盖两个实例；后台回归及导航失败后恢复已通过。真实键盘和 IME 仍待人工验收。

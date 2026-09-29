# JavaScript bridge

普通 `@JavascriptInterface fun` 同步阻塞网页 JS 并返回值。使用 Java Method 反射，不再依赖 kotlin-reflect、KType 或 Kotlin Metadata。注解 suspend 方法在注册时因 Continuation 参数被拒绝；不自动转换 Promise。

支持 String、Boolean、Byte、Short、Int、Long（JS 安全整数）、有限 Float/Double、JsonElement/JsonObject/JsonArray；void 返回 undefined。引用类型接收 null，基本类型拒绝 null，不从 Kotlin 元数据读取可空性；Kotlin 方法自身的非空检查仍可能抛异常。JsonElement 接收 JSON null 时得到 JsonNull。参数数量、JSON 类型、整数范围仍校验。只接受公开、非 static、非泛型、非 vararg 方法；不支持重载。

异步使用 addWebMessageListener(name, listener)。网页调用 name.postMessage(JSON值)，业务在自有作用域启动协程/异步任务，通过 WebMessageReply.postMessage(JSON值) 返回；网页 name.onmessage 收到 {data: JSON值}。消息 listener 在 STA 上执行，应快速派发任务，不能同步等待 UI 或反向执行 JS。异常响应、请求 ID、超时与 Promise/callback 包装由应用定义，sample 提供完整示例。

回复单次有效；导航、关闭或替换浏览器后返回 false，不发送到新文档。原生页面代数和 JS document ID 双重过滤过期响应。库不取消应用自有任务，应用负责作用域与取消；单条输入限制为 1 MiB。同步函数不强制中断超时。

Kotlin 将 invoke、navigated、close 显式注册到 Map，值分别实现 JDK Function、LongConsumer、Runnable；JNI 只按标准接口调用。JNI 的回调全局引用在 teardown 时释放。没有 JavascriptBridgeRuntime 的 keep 规则。

同步方法与消息对象、JSON 变量在文档开始时安装，先于业务脚本；仅在显式允许的顶层来源启用。原生使用实际来源校验，不能靠网页请求伪造来源绕过。

消费者规则按用户要求只包含注解方法与 JNI 入口两条。运行时扫描仍需要注解信息；sample 的 Compose Desktop 默认配置保留 RuntimeVisibleAnnotations，本库不再额外声明该规则。不能把当前验证解释为在会删除运行时注解的任意配置下也能工作。

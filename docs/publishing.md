# 本地发布

目前配置 Maven Local 发布，坐标为 `li.songe.webview2:webview2-compose:0.1.0-SNAPSHOT`。sample 不发布；native DLL 已打包进库 JAR，不需要单独发布原生模块。

## 发布

在仓库根目录执行（Windows x64，构建环境见 development.md）：

```powershell
.\gradlew.bat :webview2-compose:check :webview2-compose:publishToMavenLocal
```

生成主 JAR、sources JAR、POM 和 Gradle Module Metadata；发布的是未混淆库，应用在最终打包时混淆。DLL 和两条 consumer rules 都在主 JAR 内。Maven Local 默认位置为 `%USERPROFILE%\.m2\repository\li\songe\webview2\webview2-compose\0.1.0-SNAPSHOT\`，Maven settings 或 `maven.repo.local` 可以覆盖位置。

不需要账号、签名或远程仓库权限。基础版本统一在根目录 `build.gradle.kts` 中设置。存在 `CI` 环境变量时使用正式版本，其他环境统一追加 `-SNAPSHOT`，与执行的任务无关。不自动提供此变量的 CI/CD 平台需在流水线中设置 `CI=true`。本地构建、本地仓库发布和本机执行的远程发布都会使用快照版本，无需修改文件或传版本参数。

## 消费

在使用项目的依赖仓库中配置（如果集中管理仓库则放在 settings.gradle.kts 的 dependencyResolutionManagement 中）：

```kotlin
repositories {
    mavenLocal { content { includeGroup("li.songe.webview2") } }
    mavenCentral()
    google()
}
```

依赖：

```kotlin
implementation("li.songe.webview2:webview2-compose:0.1.0-SNAPSHOT")
```

使用者仍需 Windows x64、WebView2 Runtime、JDK 21+ 及兼容的 Kotlin/Compose 版本；不需要安装 MSVC/CMake。库本机编译工具只用于构建发布者的 DLL。API 与使用方法见 README；混淆配置见 shrinking.md。

## Maven Central

采用 priv-kit 相同的 Vanniktech Maven Publish 0.37.0 插件与发布流程。配置 `signing.keyId` 时启用 Maven Central 与签名；本地 Maven Local 不需要凭据。POM 的项目及 SCM URL 固定为 https://github.com/lisonge/compose-webview2，许可证为 Apache-2.0。

工作流沿用五个 GitHub Secrets：`OSSRH_USERNAME`、`OSSRH_PASSWORD`、`OSSRH_GPG_SECRET_KEY_ID`、`OSSRH_GPG_SECRET_KEY_PASSWORD`、`OSSRH_GPG_SECRET_FILE_BASE64`。参考 morph-compose，通过环境变量和 `-P` 参数传递凭据，不修改 `gradle.properties`；密钥解码到已被 Git 忽略的 `secring.gpg`，然后执行 `publishAndReleaseToMavenCentral --no-configuration-cache`。sources、javadoc JAR 和发布元数据由插件自动配置。

## Windows 绿色版与 GitHub Release

本地构建（不启用混淆）：

```powershell
.\gradlew.bat :webview2-sample:createDistributable
```

应用目录位于 `webview2-sample/build/compose/binaries/main/app/WebView2Sample/`。发布工作流将整个目录压缩为 ZIP。解压整个文件夹，运行 `WebView2Sample/WebView2Sample.exe`；不能只复制 EXE，旁边的 app/runtime 目录也是运行所需。包内自带 Java 运行时，不要求用户安装 Java；仍需要 Windows x64 和 WebView2 Runtime。正常运行时浏览器数据保存在 EXE 旁的 `data/profile`，因此请解压到可写目录；测试模式仍使用原有 build 目录。

`.github/workflows/release.yml` 参考 priv-kit 的发布流程，仅由推送 `v*` 标签触发，在同一个 Windows job 中依次运行检查、构建绿色版、发布 Maven Central、整理附件，并通过 `softprops/action-gh-release@v3` 创建或更新 GitHub Release。Release 名称为 `Release v版本号`。

先更新根目录 `build.gradle.kts` 的版本及 `CHANGELOG.md`，提交后可用以下命令触发（示例版本按实际发布修改）：

```powershell
git tag v0.1.0
git push origin v0.1.0
```

发布前 `verifyReleaseVersion` 校验标签必须等于 `v${project.version}`，且不允许 SNAPSHOT；版本不符会立即失败，不进入构建或发布。

GitHub Release 正文直接读取根目录 `CHANGELOG.md`；该文件只保留当前版本发布日志，发布下一版时替换内容，不累计历史版本。

GitHub Release 附件为 `webview2-sample-v版本号-windows-x64-portable.zip`；库产物发布到 Maven Central。工作流不运行需要本机图形环境的 HTTP 验收。

本地压缩绿色版：

```powershell
Compress-Archive -Path 'webview2-sample/build/compose/binaries/main/app/WebView2Sample' -DestinationPath 'webview2-sample-windows-x64-portable.zip' -Force
```

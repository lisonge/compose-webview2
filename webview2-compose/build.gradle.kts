plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
    `java-library`
    alias(libs.plugins.maven.publish)
}
kotlin {
    jvmToolchain(21)
    explicitApi()
}
dependencies {
    api(libs.compose.runtime)
    api(libs.compose.ui)
    implementation(libs.compose.foundation)
    api(libs.serialization.json)
}
val nativeResources = layout.buildDirectory.dir("generated/nativeResources")
val nativeJdk = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
val buildNative = tasks.register<Exec>("buildNative") {
    inputs.files(rootProject.fileTree("webview2-native") { exclude("build/**") })
    inputs.file(rootProject.file("scripts/build-native.ps1"))
    val javaHome = nativeJdk.get().metadata.installationPath
    inputs.property("javaHome", javaHome.asFile.absolutePath)
    inputs.dir(javaHome.dir("include"))
    inputs.file(javaHome.file("release"))
    inputs.file(javaHome.file("lib/jawt.lib"))
    outputs.dir(nativeResources)
    commandLine("pwsh", "-NoProfile", "-File", rootProject.file("scripts/build-native.ps1"),
        "-JavaHome", javaHome.asFile,
        "-OutputDirectory", nativeResources.get().asFile)
}
// Carry the native build dependency with the generated resource directory.
sourceSets.main { resources.srcDir(files(nativeResources).builtBy(buildNative)) }

mapOf(
    "nativeSmoke" to "NativeBridgeKt",
    "browserSmoke" to "BrowserSmokeKt",
    "bridgeSmoke" to "BridgeSmokeKt",
    "themeSmoke" to "ThemeSmokeKt",
    "settingsSmoke" to "SettingsSmokeKt",
    "navigationSmoke" to "NavigationSmokeKt",
    "lifecycleSmoke" to "LifecycleSmokeKt",
    "viewportSmoke" to "ViewportSmokeKt",
).forEach { (taskName, entryPoint) ->
    tasks.register<JavaExec>(taskName) {
        classpath = sourceSets[if (taskName == "nativeSmoke") "main" else "test"].runtimeClasspath
        mainClass.set("li.songe.compose.webview2.internal.$entryPoint")
    }
}
// Integration mains are opt-in JavaExec tasks, not JUnit cases.
tasks.test { failOnNoDiscoveredTests = false }

mavenPublishing {
    if (providers.gradleProperty("signing.keyId").isPresent) {
        publishToMavenCentral()
        signAllPublications()
    }
    val repoUrl = "https://github.com/lisonge/compose-webview2"
    pom {
        name.set("Compose WebView2")
        description.set("WebView2 embedded in Compose Desktop on Windows x64")
        url.set(repoUrl)
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                name.set("lisonge")
                email.set("i@songe.li")
                url.set("https://github.com/lisonge")
            }
        }
        scm { url.set(repoUrl) }
    }
}
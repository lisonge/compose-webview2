plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}
kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":webview2-compose"))
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material3)
    implementation(libs.ktor.server.cio)
    implementation(libs.serialization.json)
    implementation(libs.coroutines.swing)
}

compose.desktop {
    application {
        mainClass = "li.songe.compose.webview2.sample.MainKt"
        nativeDistributions {
            packageName = "WebView2Sample"
            packageVersion = project.version.toString().substringBefore('-')
            description = "Compose Desktop WebView2 sample"
            vendor = "li.songe"
            includeAllModules = true
        }
    }
}

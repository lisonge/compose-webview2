pluginManagement {
    repositories { gradlePluginPortal(); mavenCentral(); google() }
}
dependencyResolutionManagement {
    repositories {
        mavenCentral(); google()
    }
}
rootProject.name = "compose-webview2"
include(":webview2-compose", ":webview2-sample")

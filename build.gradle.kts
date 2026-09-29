plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose) apply false
}
val isCi = providers.environmentVariable("CI").isPresent
allprojects {
    group = "li.songe.webview2"
    version = "0.1.0" + if (isCi) "" else "-SNAPSHOT"
}

val releaseTag = providers.environmentVariable("GITHUB_REF_NAME")
val projectReleaseVersion = version.toString()
tasks.register("verifyReleaseVersion") {
    group = "verification"
    description = "Checks that the release tag matches the project version."
    doLast {
        check(!projectReleaseVersion.endsWith("-SNAPSHOT")) { "Cannot release a SNAPSHOT version" }
        check(releaseTag.orNull == "v$projectReleaseVersion") {
            "Expected tag v$projectReleaseVersion, got ${releaseTag.orNull ?: "<missing>"}"
        }
    }
}

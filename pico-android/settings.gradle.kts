pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
    resolutionStrategy {
        eachPlugin {
            // Match the official Spatial UI sample's lint/AGP API only in OS 6 builds.
            if (providers.gradleProperty("picoSpatial").orNull == "true" && requested.id.id == "com.android.application")
                useVersion("8.8.2")
        }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google(); mavenCentral()
        if (providers.gradleProperty("picoSpatial").orNull == "true") {
            maven("https://artifact.bytedance.com/repository/Volcengine") {
                content { includeGroupByRegex("com\\.pico(\\..*)?") }
            }
        }
    }
}
rootProject.name = "OpenUUYC-PICO"
include(":app")

// OS 6 dependencies must never enter the OS 5 compatibility APK.
if (providers.gradleProperty("picoSpatial").orNull == "true") include(":spatial")

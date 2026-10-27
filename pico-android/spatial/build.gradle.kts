plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
}
android {
    namespace = "org.openuuyc.pico"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    defaultConfig {
        // Same application ID/signing key preserves the encrypted login on OS upgrade.
        // These are mutually exclusive backend APKs, not two simultaneous apps.
        applicationId = "org.openuuyc.pico"
        minSdk = 35
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0-spatial-preview"
        ndk { abiFilters.add("arm64-v8a") }
    }
    sourceSets.getByName("main") {
        // Android resources remain owned by the compatibility app.
        res.srcDir("../app/src/main/res")
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(platform("com.pico.spatial:bom:6.1.9"))
    implementation("com.pico.spatial.core:core:6.1.9")
    implementation("com.pico.spatial.ui:platform")
    implementation("com.pico.spatial.ui:foundation")
    implementation("com.pico.spatial.ui:design")
    implementation("com.pico.spatial.sense:sense")
    implementation("com.pico.spatial.tracking:tracking")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.github.webrtc-sdk:android:150.7871.01")
    implementation("com.google.zxing:core:3.5.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
// PICO's official sample excludes these upstream Compose implementations because
// the Spatial UI artifacts provide their spatial-aware replacements.
configurations.all {
    exclude(group = "androidx.compose.ui", module = "ui")
    exclude(group = "androidx.compose.ui", module = "ui-graphics")
    exclude(group = "androidx.compose.ui", module = "ui-text")
    exclude(group = "androidx.compose.foundation", module = "foundation")
}

// Build-time filtered projection of the authoritative protocol source tree.
// No source fork and no legacy Activity/JNI/follow-loop classes in the OS 6 APK.
val syncSharedUuSources by tasks.registering(Sync::class) {
    from("../app/src/main/java") {
        include("org/openuuyc/pico/uu/**")
        include("org/openuuyc/pico/PicoPanelVideoView.kt")
        include("org/openuuyc/pico/PanelViewport.kt")
        include("org/openuuyc/pico/PicoTheme.kt")
    }
    into(layout.buildDirectory.dir("generated/sharedUu/kotlin"))
}
kotlin.sourceSets.getByName("main").kotlin.srcDir(syncSharedUuSources.map {it.destinationDir})

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
val selfHostNrdBase = (project.findProperty("openuuycNrdBase") as? String)?.trim().orEmpty()
if (selfHostNrdBase.isNotEmpty()) {
    val uri = java.net.URI(selfHostNrdBase)
    require(uri.scheme == "https" && uri.host != null &&
        uri.userInfo == null && (uri.path == "" || uri.path == "/") &&
        uri.query == null && uri.fragment == null && !selfHostNrdBase.contains('"')) {
        "openuuycNrdBase must be an HTTPS origin"
    }
}
android {
    namespace = "org.openuuyc.pico"
    compileSdk = 35
    defaultConfig {
        applicationId = "org.openuuyc.pico"
        testInstrumentationRunner = "org.openuuyc.pico.PicoPanelInstrumentation"
        minSdk = 29
        ndkVersion = "27.2.12479018"
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "SELFHOST_NRD_BASE", "\"" + selfHostNrdBase.trimEnd('/') + "\"")
        ndk { abiFilters.add("arm64-v8a") }
        externalNativeBuild { cmake { cppFlags += listOf("-std=c++17", "-Wall", "-Wextra") } }
    }
    buildTypes {
        getByName("release") { isMinifyEnabled = false }
        getByName("debug") { isDebuggable = true }
    }
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" }
    }
    buildFeatures { prefab = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("org.khronos.openxr:openxr_loader_for_android:1.1.63")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.github.webrtc-sdk:android:150.7871.01")
    implementation("com.google.zxing:core:3.5.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

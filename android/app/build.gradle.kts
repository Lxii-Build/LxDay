@file:Suppress("UnstableApiUsage")

plugins {
    alias(libs.plugins.agp.app)
    alias(libs.plugins.compose.compiler)
}

val androidMinSdkVersion = rootProject.extra["androidMinSdkVersion"] as Int
val androidTargetSdkVersion = rootProject.extra["androidTargetSdkVersion"] as Int
val androidCompileSdkVersion = rootProject.extra["androidCompileSdkVersion"] as Int
val androidCompileSdkVersionMinor = rootProject.extra["androidCompileSdkVersionMinor"] as Int
val androidBuildToolsVersion = rootProject.extra["androidBuildToolsVersion"] as String
val androidSourceCompatibility = rootProject.extra["androidSourceCompatibility"] as JavaVersion
val androidTargetCompatibility = rootProject.extra["androidTargetCompatibility"] as JavaVersion

// 构建期可注入（工作流 -P 参数 / gradle.properties）；缺省用官方默认值，保证本地无参构建也能过。
val propBaseUrl = (project.findProperty("BASE_URL") as String?)?.takeIf { it.isNotBlank() }
    ?: "https://love.lxii.cc/api/v1"
val propWsUrl = (project.findProperty("WS_URL") as String?) ?: ""
val propAppKey = (project.findProperty("APP_KEY") as String?) ?: ""
val propVersionName = (project.findProperty("VERSION_NAME") as String?)?.takeIf { it.isNotBlank() }
    ?: "1.0.0"
val propVersionCode = (project.findProperty("VERSION_CODE") as String?)?.toIntOrNull() ?: 1

// ---- 正式签名（PKCS#12）----
// 每次构建的 APK 签名必须完全一致，否则装不上/覆盖不了已有版本。
// 此前 release 直接复用 debug 签名（signingConfigs.getByName("debug")），
// 而 CI runner 每次都是全新机器、~/.android/debug.keystore 每次自动重新生成
// → 每次构建指纹都不同，这就是「每次生成的 APK 签名都不一样」的根因。
//
// 密钥库经 CI 从 Secret ANDROID_KEYSTORE_BASE64 解码落盘后用 -P 注入路径。
// Secret 缺失时（本地无参构建 / fork）自动回退 debug 签名，保证仍可编译。
val propKeystoreFile = (project.findProperty("KEYSTORE_FILE") as String?)?.takeIf { it.isNotBlank() }
val propKeystorePassword = (project.findProperty("KEYSTORE_PASSWORD") as String?) ?: ""
val propKeyAlias = (project.findProperty("KEY_ALIAS") as String?) ?: ""
val propKeyPassword = (project.findProperty("KEY_PASSWORD") as String?) ?: ""

// 固定的 debug 密钥库：随仓库提交（debug 密钥按安卓惯例是公开的，口令固定 android）。
// 目的是让本地与 CI 的 debug APK 签名也一致，调试时不必卸载重装丢数据。
val debugKeystore = rootProject.file("keystore/lxday-debug.p12")

android {
    namespace = "com.linxi.diary"

    signingConfigs {
        if (propKeystoreFile != null) {
            create("release") {
                storeFile = file(propKeystoreFile)
                storeType = "PKCS12"
                storePassword = propKeystorePassword
                keyAlias = propKeyAlias
                keyPassword = propKeyPassword
                // v1 已废弃且拖慢构建；v2/v3 覆盖全部支持机型，v4 便于增量安装。
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
        if (debugKeystore.exists()) {
            getByName("debug") {
                storeFile = debugKeystore
                storeType = "PKCS12"
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // 有正式密钥就用，否则回退 debug（保证无 Secret 也能出包）
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileSdk {
        version = release(androidCompileSdkVersion) {
            minorApiLevel = androidCompileSdkVersionMinor
        }
    }
    buildToolsVersion = androidBuildToolsVersion

    defaultConfig {
        applicationId = "com.linxi.diary"
        minSdk = androidMinSdkVersion
        targetSdk = androidTargetSdkVersion
        versionCode = propVersionCode
        versionName = propVersionName

        // 安全模式：true = 启动极简 UI（跳过主题/backdrop/miuix），用于闪退二分定位
        // 构建期注入的服务端地址 / WS 地址 / 通讯密钥（空则运行时回退推导 / 不发密钥头）
        buildConfigField("String", "BASE_URL", "\"$propBaseUrl\"")
        buildConfigField("String", "WS_URL", "\"$propWsUrl\"")
        buildConfigField("String", "APP_KEY", "\"$propAppKey\"")
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = androidSourceCompatibility
        targetCompatibility = androidTargetCompatibility
    }

    packaging {
        resources {
            excludes += setOf("META-INF/**", "kotlin/**", "**.bin")
        }
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
        )
    }
}

dependencies {
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)

    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)

    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.okhttp)

    // 相册：网络图加载（内存+磁盘缓存、自动采样）与 EXIF 方向读取
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.androidx.exifinterface)

    // miuix（小米 HyperOS 风格组件）
    implementation(libs.miuix.ui)
    implementation(libs.miuix.icons)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.squircle)
    implementation(libs.miuix.blur)

    // KernelSU 同款动态取色
    implementation(libs.material.kolor)

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

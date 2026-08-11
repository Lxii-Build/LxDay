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

android {
    namespace = "com.linxi.diary"

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
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
        buildConfigField("boolean", "SAFE_MODE", "false")
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

    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.okhttp)

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

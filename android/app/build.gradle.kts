// Hachimi · app 模块
// M1'：Kotlin 壳（AccessibilityService + MediaProjection + 前台服务 + GateUI）
// M2'：接入 Chaquopy（com.chaquo.python 插件 + kernel/ 源集）——红线 R7 依赖白名单管 kernel/，android/ 依赖走评审
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.hachimi.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.hachimi.app"
        minSdk = 30          // 架构 A6：Android 11+ 基线（takeScreenshot API 30+）
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // TODO(M2'): 回环 HTTP 服务端选型（NanoHTTPD vs 手写 ServerSocket），走 android/ 依赖评审
}

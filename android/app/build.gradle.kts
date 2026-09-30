// Hachimi · app 模块
// M1'：Kotlin 壳（AccessibilityService + MediaProjection + 前台服务 + GateUI）
// M2'：Chaquopy 接入（内核源集 = ../../kernel，红线 R7 依赖白名单管 kernel/，android/ 依赖走评审）
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
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
        ndk {
            // 研究真机为 arm64；x86_64（模拟器）体积翻倍，M3' 需要时再加
            abiFilters += listOf("arm64-v8a")
        }
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
    // P1 对话流列表（redesign_plan 决策：聊天列表必须回收复用；androidx 同族走评审惯例）
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    // TODO(M2' 后续): 回环 HTTP 服务端选型（NanoHTTPD vs 手写 ServerSocket），走 android/ 依赖评审
    // JVM 单测（redesign_plan：无真机环境，纯函数逻辑用单测覆盖；
    // junit 仅进 test classpath，不触 R7 kernel 白名单）
    testImplementation("junit:junit:4.13.2")
}

// Chaquopy：Python 内核源集（hachimi_kernel 包）+ 构建解释器（便携版 3.12）
// 内核仅标准库（红线 R7），无 pip requirements。
chaquopy {
    defaultConfig {
        // Python 解释器：优先用 -PhachimiPython 覆盖；默认仓库内 .tools/python312（junction 到本机 Python 3.12）。
        val hachimiPython = if (providers.gradleProperty("hachimiPython").isPresent)
            providers.gradleProperty("hachimiPython").get() else
            rootDir.resolve(".tools/python312/python.exe").toString()
        buildPython(hachimiPython)
    }
    sourceSets {
        getByName("main") {
            srcDir("../../kernel")
        }
    }
}

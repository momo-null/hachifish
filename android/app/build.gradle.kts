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
// [spike/full-kernel-hosting] Phase 0：完整内核上机三道闸 —— 目标 Python 显式钉 3.12
// （与 pydantic-core android wheel cp312 / buildPython 3.12 三方配对）；pip 装纯
// Python 依赖面 + psutil（官方仓有 Android 构建）+ 社区 pydantic-core wheel。
// openai-agents 暂不装：其依赖 jiter 暂无 Android wheel（Phase 1 项），GATE_B2 会
// 以「预期阻塞」落 logcat。
chaquopy {
    defaultConfig {
        // Python 解释器：-PhachimiPython=<python3.12> 优先；其次仓内 .tools/python312
        // （本机便捷位）；否则回退 PATH 上的 python——**必须 3.12**（与目标
        // version="3.12" 及二进制 wheel 三方配对，3.11/3.13 会构建失败）。
        val hachimiPython = if (providers.gradleProperty("hachimiPython").isPresent)
            providers.gradleProperty("hachimiPython").get() else
            rootDir.resolve(".tools/python312/python.exe").takeIf { it.exists() }?.toString()
                ?: "python"
        buildPython(hachimiPython)
        version = "3.12"   // 环境标签 android_2x_arm64_v8a；minSdk 30 ≥ wheel 的 API 24 要求
        pip {
            options("--extra-index-url", "https://pypi.tuna.tsinghua.edu.cn/simple")   // 本机直连 pypi 慢（2026-10-31 实测）；extra 保住 Chaquopy 原生仓
            install("pydantic==2.13.3")   // 与本地 pydantic-core wheel 严格配对（==2.46.3）
            install("fastapi")
            install("uvicorn")
            install("httpx")
            install("PyYAML")
            // 客体插件面 web/plugins/web/plugin.py 的模块级依赖（filesystem 纯标准库）。
            //  requests 及其 4 个依赖全为 py3-none-any 纯 wheel（PyPI 核实 2026-11-04），
            // 无 ABI 门槛；插件默认关（R5），装它只为「知识→工具」里一开就能用。
            install("requests")
            install("psutil")             // chat_runtime 兼容 shim 链（diagnostics）需要
            install("numpy==1.26.2")      // utils/json_utils 模块级依赖（官方仓 cp312 android 构建）
            // 社区 Android 构建（Eutalix，maturin/NDK r25b；WHEEL 内部标签
            // android_24_arm64_v8a，DT_NEEDED libpython3.12.so 与 3.12 目标配对）
            install("spike-wheels/pydantic_core-2.46.3-cp312-cp312-android_24_arm64_v8a.whl")
            install("spike-wheels/jiter-0.18.0-cp312-cp312-android_24_arm64_v8a.whl")
            // SDK 闭包与 PC venv 逐版对齐（R6：漂移 = bug）
            install("openai-agents==0.22.2")
            install("openai==3.13.0")
            install("mcp==2.2.0")
            install("httpx2==2.12.0")
            install("websockets==15.0.1")
            install("spike-wheels/rpds_py-0.30.0-cp312-cp312-android_24_arm64_v8a.whl")  // mcp→jsonschema 链（自编）
            install("spike-wheels/ormsgpack-1.12.2-cp312-cp312-android_24_arm64_v8a.whl")  // checkpoint 依赖（自编，PC 同版 1.12.2）
            install("spike-wheels/orjson-3.11.7-cp312-cp312-android_24_arm64_v8a.whl")      // langgraph-sdk 依赖（自编，PC 同版 3.11.7）
            install("langgraph-sdk==0.3.11")
            install("langgraph-checkpoint==4.0.1")
            install("langgraph-prebuilt==1.0.8")   // PC 同版
            install("langchain-core==1.2.19")      // PC 同版
            install("spike-wheels/uuid_utils-0.14.1-cp39-abi3-android_24_arm64_v8a.whl")  // langchain-core 依赖（自编，PC 同版）
            install("jsonschema==4.26.0")
            install("spike-wheels/xxhash-3.6.0-py3-none-any.whl")  // langgraph 依赖的 xxh3_128 指纹：blake2b-128 语义替身（见方案文档）
            install("spike-wheels/zstandard-0.25.0-py3-none-any.whl")  // langsmith 依赖：stub（压缩守卫关闭，见方案文档）
            install("langgraph==1.1.2")   // run_task 必经（graph_runner→orchestration）；PC 同版
        }
    }
    sourceSets {
        getByName("main") {
            srcDir("../../kernel")
            // src/main/python 为 Chaquopy 隐式源集：spike 自检模块 +
            // 构建期从上游仓拷入的 omni_core/backend/utils/config 源码（见 spike 说明）
        }
    }
}

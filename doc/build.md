# 构建指南（Build）

> 目标产物：`android/app/build/outputs/apk/debug/app-debug.apk`（arm64-v8a debug 包）。

## 前置

| 依赖 | 版本 | 说明 |
|---|---|---|
| JDK | 17 | `JAVA_HOME` 指向 JDK 17 |
| Android SDK | compileSdk 36 | Android Studio 默认装齐即可 |
| Python | **3.12** 64-bit，`python` 在 PATH | Chaquopy buildPython；**必须 3.12**（与 `version="3.12"` 及二进制 wheel 三方配对，3.11/3.13 直接构建失败） |
| 上游源码 | OmniAgent 仓 | 内核源码单一真源（构建期拷入，不入本仓） |

NDK 不需要单独装（Chaquopy 运行库与 `spike-wheels/` 均为预构建产物；仅当要自建
wheel 时才需 rust + NDK，见 `doc/full_kernel_hosting_pitfalls_2026-10-31.md` §2.2）。

## 步骤

```bash
# 1. 同步上游内核源码进 Android 源集（三选一）
python scripts/sync_upstream.py --upstream <OmniAgent 工作区路径>
python scripts/sync_upstream.py --git https://github.com/momo-null/OmniAgent.git
OMNIAGENT_UPSTREAM=<路径> python scripts/sync_upstream.py

# 2. 构建（Python 3.12 在 PATH 时无需 -P）
./gradlew assembleDebug
#    本机 python 不是 3.12 时：
./gradlew assembleDebug -PhachimiPython="<python3.12 路径>"
```

上游改动后重跑第 1 步即可生效；脚本先清后拷，不会残留陈旧文件。

## 说明

- `spike-wheels/`：预构建 Android wheel（pydantic-core / jiter / rpds-py /
  ormsgpack / orjson / uuid-utils 为 Rust 交叉编译产物，xxhash/zstandard 为
  语义替身），已随库分发，clone 即可构建。
- 上游对拍：`kernel/` 仅标准库红线由 `scripts/check_redlines.py` 把关。
- 本机便捷位：`.tools/python312/python.exe`（junction）存在时优先于 PATH——
  仅本机有效，仓库不含该目录。

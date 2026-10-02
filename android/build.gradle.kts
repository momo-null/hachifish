// Hachimi · 根构建脚本
// 版本已经 Google Maven / Maven Central 元数据核实（2026-09-26）：
// AGP 8.13.2 = 8.x 稳定线末版，支持 compileSdk 36，保留经典 kotlin-android 插件；Gradle 8.13 起。
// Chaquopy 17.0.0：官方文档 AGP 兼容 7.3.x–9.2.x，分发在 mavenCentral。
plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
    id("com.chaquo.python") version "17.0.0" apply false
}

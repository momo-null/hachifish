// Hachimi · settings.gradle.kts
// M1' 骨架：纯 Kotlin（google()/mavenCentral()）。Chaquopy 插件与 Python 源集在 M2' 接入。
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "Hachimi"
include(":app")

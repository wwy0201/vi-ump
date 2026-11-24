pluginManagement {
    repositories {
        // 国内镜像优先（本机 GRADLE_USER_HOME 的 init 脚本也会注入同样的仓库，
        // 这里显式声明是为了工程可移植：换机器也能快速解析依赖）
        mavenLocal()
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "vi-ump"

dependencyResolutionManagement {
    // PREFER_SETTINGS 而非 FAIL_ON_PROJECT_REPOS：
    // 本机 init.d/mirrors.gradle 会向 project 注入 mavenLocal，
    // FAIL_ON_PROJECT_REPOS 会直接让构建失败；PREFER_SETTINGS 下以本节为准，注入被忽略。
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenLocal()
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
    }
}

// ── Stage 3 目标结构：11 个模块 ────────────────────────────────
// 若按 Stage 1（6 模块）推进，可先注释掉 core:query / core:config /
// core:source / core:storage / core:player，把它们并入 :core:engine 与 :core:platform。
include(":app:android")
include(":app:desktop")
include(":app:tv")

include(":core:model")
include(":core:common")
include(":core:query")
include(":core:config")
include(":core:source")
include(":core:storage")
include(":core:player")
include(":core:ui")

include(":render")

include(":feature:main")

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
}

// 说明：SQLDelight / multiplatform-settings 当前并未实际使用（设置走 SharedPrefs/Preferences 的 actual 实现）。
// TV 4.4（API 19）要求共享模块不能带 minSdk=21 的 AAR，因此这里不再声明这两个依赖，
// 等真正落地 .sq 表结构时再按需引入（并确认目标 API）。

kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    jvm()

    sourceSets {
        commonMain.dependencies {
            api(project(":core:model"))
            api(project(":core:common"))
        }
    }
}

android {
    namespace = "com.vi.ump.storage"
    compileSdk = 36
    defaultConfig { minSdk = 19 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

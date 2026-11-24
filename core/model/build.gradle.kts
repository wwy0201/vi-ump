plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    jvm() // 允许在 JVM 上跑单测与 CLI 验证，不依赖 Android 设备

    sourceSets {
        commonMain.dependencies {
            // 仅此一个依赖：配置模型的 @Serializable 注解需要它
            api(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

android {
    namespace = "com.vi.ump.model"
    compileSdk = 36
    defaultConfig { minSdk = 19 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

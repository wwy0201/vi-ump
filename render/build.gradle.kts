plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    jvm()

    sourceSets {
        commonMain.dependencies {
            api(project(":core:model"))
            api(project(":core:ui"))
            api(project(":core:source"))
            api(project(":core:player"))
            // 图片解密复用 core:query 的 CryptoCodec；显式声明，避免将来 core:source 改 implementation 后断链
            api(project(":core:query"))
            api(compose.runtime)
            api(compose.foundation)
            api(compose.material3)
            implementation(libs.coil3.compose)
        }
        androidMain.dependencies {
            implementation(libs.coil3.network.okhttp)
        }
        jvmMain.dependencies {
            implementation(libs.coil3.network.okhttp)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

android {
    namespace = "com.vi.ump.render"
    compileSdk = 36
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
}

kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    jvm()

    sourceSets {
        commonMain.dependencies {
            api(project(":core:model"))
            api(project(":core:common"))
            api(libs.kotlinx.coroutines.core)
        }
        androidMain.dependencies {
            // api 而非 implementation：ExoPlayer 句柄要经 renderToken/AudioSession 交给 app 壳层，
            // 壳层的 MediaSession 与 PlayerView 都得拿到这个类型
            api(libs.media3.exoplayer)
            implementation(libs.media3.exoplayer.hls)
            implementation(libs.media3.ui)
            implementation(libs.media3.datasource.okhttp)
        }
        jvmMain.dependencies {
            // vlcj 4：直接调本机 VLC 的 libvlc，解封装/解码交给 VLC
            implementation(libs.vlcj)
            // Windows 下 vlcj 靠注册表找 VLC 安装目录，这条链路需要 jna-platform
            implementation(libs.jna.platform)
        }
    }
}

android {
    namespace = "com.vi.ump.player"
    compileSdk = 36
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

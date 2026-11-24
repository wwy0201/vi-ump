import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

/**
 * 发布签名（2026-11-24）：从仓库根目录的 `keystore.properties` 读，**文件不存在就不签名**。
 *
 * 为什么走属性文件而不是把口令写进 build.gradle.kts：
 *  - 正式签名是身份凭证，进 git 等于泄露；`keystore.properties` 和 `*.jks` 都在 .gitignore 里；
 *  - 本地/CI 各自放一份即可，构建脚本行为一致。
 *
 * 没有该文件时 `assembleRelease` 仍能跑通（产出 unsigned APK，装不上），不会把 debug 包当正式包发出去。
 * 参数说明见仓库 `keystore.properties` 与 README「发布签名」一节。
 */
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val hasReleaseKeystore = keystorePropsFile.exists()

/** 版本号唯一来源（versionName 与打包文件名都用它） */
val appVersionName = "1.0.0"
val appVersionCode = 1

android {
    namespace = "com.vi.ump.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.vi.ump"
        minSdk = 24
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
    }

    buildFeatures {
        compose = true
    }

    /**
     * M5.9：**不再把仓库 `samples` 目录挂进 assets**。
     *
     * 以前这里 `assets.srcDir(rootProject.file("samples"))` 把样例配置打进 APK 当"预置源"，
     * 现在源**完全由用户导入**（设置页的文件 / 粘贴 / URL），APK 里不带任何站点配置：
     *  - 预置源是没人维护的样例，装完看到一堆点开没数据的源，观感就是"App 坏了"；
     *  - 站点配置一直在演化，钉死在 APK 里等于一装就过期；
     *  - 不带配置的包也更小、更干净，仓库里的 `samples/` 回归"开发期样例"的本分。
     */

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                // minSdk 24：v2/v3 就够；v1 关掉，免得"已启用 JAR 签名"的告警
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            // 调试包永久用 debug keystore，换机联调不用重新卸载
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // 没配 keystore.properties 时不挂签名，产物是 unsigned，装不上——这是有意的
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}

/**
 * 打包产物带版本号（2026-11-24）：
 * `vi-ump-android-1.0.0-debug.apk` / `vi-ump-android-1.0.0-release.apk`，
 * 装了几台机器、发给别人时不用再翻 build.gradle 确认版本。
 * 版本号唯一来源仍是上面的 `appVersionName`。
 *
 * 注：AGP 8 里 `VariantOutput` 公共接口只读，重命名要走 `VariantOutputImpl`；
 * `base.archivesName` 对 APK 不生效（只影响 AAR/JAR），所以这里用后者。
 */
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            (output as? com.android.build.api.variant.impl.VariantOutputImpl)
                ?.outputFileName
                ?.set("vi-ump-android-$appVersionName-${variant.buildType ?: "debug"}.apk")
        }
    }
}

dependencies {
    implementation(project(":feature:main"))
    implementation(project(":core:ui"))
    implementation(project(":core:common"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.koin.android)
    implementation(libs.koin.compose)

    /** 画面承载：core:player 只交出 ExoPlayer 句柄，PlayerView 挂载属于壳层 */
    implementation(libs.media3.ui)

    /** 音频常驻通知栏（M4.5）：MediaSession 让锁屏/蓝牙耳机键也能控制 */
    implementation(libs.media3.session)

    /** URL 拉取导入（M5）：壳层直接发 HTTP 拿配置文本，超时与状态检查在 feature 的 fetchConfigText */
    implementation(libs.ktor.client.okhttp)

    /** 备份源（M5.9）：SAF 拿到的 tree uri 要靠 DocumentFile 列目录、建文件、覆盖同名文件 */
    implementation(libs.androidx.documentfile)

    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(compose.material3)
    implementation(compose.ui)
    debugImplementation(compose.uiTooling)
}

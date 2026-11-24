import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
}

/**
 * TV 版（Android 4.4.2 / API 19）独立壳。
 *
 * 为什么不能复用 `app:android`：
 *  - Compose UI 1.9 / media3 / Coil3 / core-ktx 全部声明 minSdk=21，API 19 装不上也跑不了；
 *  - 4.4 默认 Dalvik，64K 方法数要靠 multidex；
 *  - 这里只共享 core 的配置编译、抽取引擎与存储，界面用原生 View。
 */
/** 发布签名：与手机端共用仓库根目录的 keystore.properties（缺失时 release 不签名） */
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val hasReleaseKeystore = keystorePropsFile.exists()

/** 版本号唯一来源（versionName 与打包文件名都用它） */
val tvVersionName = "1.0.0"
val tvVersionCode = 1

android {
    namespace = "com.vi.ump.tv"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.vi.ump.tv"
        // Android 4.4.2 = API 19
        minSdk = 19
        // 老盒子只跑 4.4；如以后要上 5.x/7.x 再开 flavor 调高 targetSdk
        targetSdk = 19
        versionCode = tvVersionCode
        versionName = tvVersionName

        multiDexEnabled = true
        // 老盒子基本都是 32 位 ARMv7；x86 只为 4.4 模拟器联调
        ndk { abiFilters += listOf("armeabi-v7a", "x86") }
    }

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
                // minSdk 19：Android 4.4 只认 v1（JAR）签名，v1 必须开；v2 给高版本用
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = false
            }
        }
    }

    /**
     * TV 包只走 sideload（盒子/模拟器），不上 Google Play，所以关掉 release 的 lint 阻断：
     * `targetSdk = 19` 会触发 ExpiredTargetSdkVersion 致命错误，而这正是 4.4 盒子要的配置。
     */
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    buildTypes {
        debug {
            // 4.4 只认 v1 签名；minSdk<24 时 AGP 默认会开，这里显式写清楚
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:query"))
    implementation(project(":core:config"))
    implementation(project(":core:source")) {
        // 手机版的 HttpFetcher 走 Ktor/OkHttp4；TV 不引用它，
        // 但 Gradle 会把 Ktor 的 OkHttp4 传递进来顶掉 3.12，必须排掉
        exclude(group = "io.ktor")
        exclude(group = "com.squareup.okhttp3")
    }
    implementation(project(":core:storage"))

    // 4.4 能跑的网络栈：OkHttp 4.x 官方下限是 API 21，这里必须用 3.12
    implementation(libs.legacy.okhttp)
    // 4.4 默认不启用 TLS 1.2，且信任库缺新根：用 Conscrypt 提供 TLS 1.2/1.3
    implementation(libs.conscrypt.android)
    // Dalvik 64K method 限制
    implementation(libs.androidx.multidex)

    // 4.4 可用的播放器：ExoPlayer2 的 minSdk 是 16，且不依赖 Compose / Media3
    implementation(libs.exoplayer2.core)
    implementation(libs.exoplayer2.hls)
    implementation(libs.exoplayer2.ui)
}

/** 双保险：任何传递依赖再想把 okhttp 抬到 4.x，都在 TV 这条线强制回 3.12 */
configurations.all {
    resolutionStrategy.force("com.squareup.okhttp3:okhttp:3.12.13")
    resolutionStrategy.force("com.squareup.okio:okio:1.17.6")
}

/** 打包产物带版本号：`vi-ump-tv-0.1.0-debug.apk`（版本号来源同上；APK 改名同样走 VariantOutputImpl） */
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            (output as? com.android.build.api.variant.impl.VariantOutputImpl)
                ?.outputFileName
                ?.set("vi-ump-tv-$tvVersionName-${variant.buildType ?: "debug"}.apk")
        }
    }
}

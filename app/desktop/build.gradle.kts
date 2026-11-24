import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    jvm()

    sourceSets {
        jvmMain.dependencies {
            implementation(project(":feature:main"))
            implementation(project(":core:ui"))
            implementation(project(":core:common"))
            implementation(compose.desktop.currentOs)
            implementation(libs.coil3.network.okhttp)
            implementation(libs.koin.core)
            // URL 拉取导入（M5）：与 Android 壳层同一份 fetchConfigText
            implementation(libs.ktor.client.okhttp)
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.vi.ump.desktop.MainKt"

        nativeDistributions {
            // Windows: MSI（需 WiX Toolset）；macOS: DMG；Linux: DEB
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb)
            // 注意：安装包名（packageName）必须是 ASCII。jpackage 读参数文件按 ANSI 解码，
            // 写中文会让它直接抛 "Input length = 1" 打包失败；中文显示名请在应用内/安装后改，
            // 或后续用 WiX 的 DisplayName 定制（这里不放进构建链）。
            packageName = "vi-ump"
            packageVersion = "1.0.0"
            description = "Config-driven media client (vi-ump)"
            vendor = "vi-ump"

            // 安装包/快捷方式图标：Windows 用 .ico（多尺寸内嵌），Linux 用 .png
            windows {
                iconFile.set(project.file("icons/vi-ump.ico"))
            }
            linux {
                iconFile.set(project.file("icons/vi-ump.png"))
            }
        }
    }
}

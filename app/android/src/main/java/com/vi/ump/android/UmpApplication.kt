package com.vi.ump.android

import android.app.Application
import android.content.Intent
import com.vi.ump.player.AndroidPlayerHost
import com.vi.ump.player.AudioControlBus
import com.vi.ump.player.AudioSession
import com.vi.ump.storage.AndroidStorageHost

class UmpApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // 播放器需要 applicationContext 才能建 ExoPlayer，
        // 而 expect fun createPlayerFacade() 不带参数 —— 所以在进程最早期注入。
        // 只存 applicationContext，不持有 Activity（旋转屏幕不会泄漏）。
        AndroidPlayerHost.install(this)

        // 进度存储同理：SharedPreferences 要 Context，而 createProgressStore() 不带参数。
        // 不注入的话会退回内存实现 —— 不崩，但进度记不住。
        AndroidStorageHost.install(this)

        // 音频前台服务（M4.5）：core:player 不依赖 app，所以"怎么拉起服务"由这里注入。
        // 起播音频时 Media3Player 会调 AudioSession.requestServiceStart()。
        AudioSession.serviceStarter = { ctx -> AudioPlaybackService.start(ctx) }

        // 用户关闭"音乐后台播放"后退后台：只停服务/通知，不释放共享播放器。
        // 回到前台再点播放时 Media3Player.play() 会重新拉起服务。
        AudioSession.serviceStopper = { ctx ->
            ctx.stopService(Intent(ctx, AudioPlaybackService::class.java))
        }

        // 设置页切换「音乐后台播放」时立即生效：
        // 关闭 → 撤服务/通知（音频本身继续在前台播）；打开且正在播 → 重新拉起服务。
        AudioControlBus.onBackgroundPlayChanged = { enabled ->
            if (enabled) {
                if (AudioSession.peek()?.playWhenReady == true) {
                    AudioSession.requestServiceStart(this)
                }
            } else {
                AudioSession.requestServiceStop(this)
            }
        }

        // "彻底停掉音频"（换源时由 AppRoot 调用）：释放共享播放器 + 停服务 + 撤通知。
        AudioControlBus.requestStop = {
            AudioSession.releaseShared()
            stopService(Intent(this, AudioPlaybackService::class.java))
        }

        // TODO(M2/M5)：在此初始化依赖容器
        //  - SourceRegistry   （多源配置注册表，SQLDelight 读取 source 表）
        //  - ConfigCompiler    （配置编译入口）
        //  - HttpEngine        （Ktor + OkHttp）
        // 采用 Koin，模块声明分散在各 core 模块的 di/*.kt 中，此处统一 startKoin。
    }
}

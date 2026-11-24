package com.vi.ump.tv

import android.app.Application
import androidx.multidex.MultiDexApplication
import com.vi.ump.storage.AndroidStorageHost

/**
 * TV 壳的 Application。
 *
 * 4.4 默认 Dalvik：方法数超过 64K 时必须走 multidex，所以继承 MultiDexApplication。
 * 播放器不在 Application 里初始化——TV 音频/视频都只在前台播放，随 Activity 生命周期走。
 */
class TvApplication : MultiDexApplication() {

    override fun onCreate() {
        super.onCreate()
        // SharedPreferences 需要 Context；不注入会退回内存实现（能跑但进度记不住）
        AndroidStorageHost.install(this)
        TlsSupport.install(this)
    }
}

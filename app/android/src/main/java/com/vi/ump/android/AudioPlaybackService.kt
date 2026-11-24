package com.vi.ump.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaSession
import com.vi.ump.player.AudioControlBus
import com.vi.ump.player.AudioSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 音频前台服务（M4.5）：常驻通知栏 + MediaSession。
 *
 * 通知栏是**两层自定义布局**（用户指定），不是系统 MediaStyle 的单行紧凑条：
 * - 第一层：当前曲目名（超长省略） + 最右侧"退出"；
 * - 第二层：上一首 / 暂停·播放 / 下一首。
 *
 * 分工：播放器实例在 `AudioSession`（core:player），播放列表与切歌逻辑在 `AudioRoute`，
 * 本服务只负责"把按钮事件转交给 `AudioControlBus`、把标题/播放态画到通知上"。
 * 这样服务不需要知道任何站点、详情、曲目结构。
 */
class AudioPlaybackService : Service() {

    companion object {
        private const val CHANNEL_ID = "audio_playback"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.vi.ump.audio.START"
        const val ACTION_PREV = "com.vi.ump.audio.PREV"
        const val ACTION_TOGGLE = "com.vi.ump.audio.TOGGLE"
        const val ACTION_NEXT = "com.vi.ump.audio.NEXT"
        const val ACTION_EXIT = "com.vi.ump.audio.EXIT"

        fun start(context: Context) {
            val intent = Intent(context, AudioPlaybackService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }
    }

    private var mediaSession: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var foreground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // NotificationChannel 是 API 26 才有的类：手机版 minSdk=24，
        // Android 7.0/7.1 直接 new 会 NoClassDefFoundError（一播放音频就崩）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) createChannel()
        ensureSession()
        // 路由侧的标题/播放态变化 → 重发通知（不走轮询）
        scope.launch {
            AudioControlBus.refreshEvents.collect {
                if (foreground) notifyNow()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PREV -> AudioControlBus.onPrev()
            ACTION_NEXT -> AudioControlBus.onNext()
            ACTION_TOGGLE -> AudioControlBus.onToggle()
            ACTION_EXIT -> {
                exitAll()
                return START_NOT_STICKY
            }
            else -> {
                ensureSession()
                startForeground(NOTIFICATION_ID, buildNotification())
                foreground = true
            }
        }
        notifyNow()
        return START_STICKY
    }

    /** "退出"：停播 + 关通知 + 停服务 + 通知 App 退出音频页 */
    private fun exitAll() {
        foreground = false
        AudioControlBus.exitEvents.value += 1
        AudioSession.releaseShared()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        foreground = false
        scope.cancel()
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }

    private fun ensureSession() {
        if (mediaSession == null) {
            AudioSession.peek()?.let { mediaSession = MediaSession.Builder(this, it).build() }
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "音频播放",
            NotificationManager.IMPORTANCE_LOW, // 常驻条不该每次刷新都响一下
        ).apply { description = "音频后台播放与通知栏控制" }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    private fun notifyNow() {
        if (!foreground) return
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification())
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, AudioPlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun buildNotification(): Notification {
        val views = RemoteViews(packageName, R.layout.notification_audio)
        views.setTextViewText(R.id.audio_title, AudioControlBus.title())
        views.setImageViewResource(
            R.id.audio_play_pause,
            if (AudioControlBus.isPlaying()) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_media_play,
        )
        views.setOnClickPendingIntent(R.id.audio_close, servicePendingIntent(ACTION_EXIT, 1))
        views.setOnClickPendingIntent(R.id.audio_prev, servicePendingIntent(ACTION_PREV, 2))
        views.setOnClickPendingIntent(R.id.audio_play_pause, servicePendingIntent(ACTION_TOGGLE, 3))
        views.setOnClickPendingIntent(R.id.audio_next, servicePendingIntent(ACTION_NEXT, 4))

        // 点通知本体回到 App（不新建任务）
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            // 不能用 applicationInfo.icon：本工程没设应用图标，那个值是 0，
            // startForeground 会直接抛 "Invalid notification (no valid small icon)"（模拟器实测崩过一次）
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(AudioControlBus.title())
            .setContentText("ViUmp 音频播放")
            .setCustomContentView(views)
            .setCustomBigContentView(views)
            .setContentIntent(contentIntent)
            .setOngoing(true)           // 常驻，不能被滑掉
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC) // 锁屏也可见
            .build()
    }
}

package cn.edu.sicnu.cs.sjh.seventh_work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle

/**
 * 音乐播放服务。
 *
 * 所有与播放相关的逻辑（播放、暂停、停止、上一首、下一首、seek）都在这里完成；
 * Activity 通过 bindService 拿到 [MusicBinder]，从而读取歌曲名、进度等状态。
 */
class MusicService : Service() {

    companion object {
        private const val TAG = "MusicService"
        private const val CHANNEL_ID = "music_playback"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_PREVIOUS = "cn.edu.sicnu.cs.sjh.seventh_work.action.PREVIOUS"
        const val ACTION_TOGGLE = "cn.edu.sicnu.cs.sjh.seventh_work.action.TOGGLE"
        const val ACTION_NEXT = "cn.edu.sicnu.cs.sjh.seventh_work.action.NEXT"
    }

    private val binder = MusicBinder()
    private lateinit var player: MediaPlayer
    private lateinit var notificationManager: NotificationManager

    private var songs: List<Song> = emptyList()
    private var currentIndex = -1
    private var isPrepared = false
    private var isForeground = false

    private val audioAttributes: AudioAttributes by lazy {
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
    }

    /**
     * 提供给 Activity 的 Binder。Activity 通过这些方法获取歌曲名、播放进度，
     * 也通过它们控制播放。
     */
    inner class MusicBinder : Binder() {

        fun setSongs(list: List<Song>) {
            // Activity 可能在每次 onServiceConnected 时都会传同一份列表，
            // 内容相同就不要重置，避免打断正在播放的歌曲。
            if (songs == list) return
            songs = list
            if (currentIndex !in songs.indices) {
                currentIndex = -1
            }
        }

        fun getSongs(): List<Song> = songs

        fun getCurrentSong(): Song? = songs.getOrNull(currentIndex)

        fun getCurrentIndex(): Int = currentIndex

        fun isPrepared(): Boolean = this@MusicService.isPrepared

        fun isPlaying(): Boolean = this@MusicService.isPrepared && player.isPlaying

        fun getPosition(): Int =
            if (this@MusicService.isPrepared) {
                runCatching { player.currentPosition }.getOrDefault(0)
            } else {
                0
            }

        fun getDuration(): Int =
            if (this@MusicService.isPrepared) {
                runCatching { player.duration }.getOrDefault(0)
            } else {
                getCurrentSong()?.durationMs?.toInt() ?: 0
            }

        fun playSong(index: Int) = startSong(index)

        fun togglePlayPause() {
            when {
                !this@MusicService.isPrepared -> {
                    // 还没有准备歌曲（第一次播放 / 停止后重新播放）
                    val index = if (currentIndex in songs.indices) currentIndex else 0
                    if (songs.isNotEmpty()) startSong(index)
                }

                player.isPlaying -> {
                    player.pause()
                    refreshNotification()
                }

                else -> {
                    player.start()
                    ensureForeground()
                    refreshNotification()
                }
            }
        }

        fun playPrevious() {
            if (songs.isEmpty()) return
            val index = if (currentIndex >= 0) {
                (currentIndex - 1 + songs.size) % songs.size
            } else {
                0
            }
            startSong(index)
        }

        fun playNext() {
            if (songs.isEmpty()) return
            val index = if (currentIndex >= 0) {
                (currentIndex + 1) % songs.size
            } else {
                0
            }
            startSong(index)
        }

        fun seekTo(positionMs: Int) {
            if (!this@MusicService.isPrepared) return
            val duration = getDuration()
            if (duration > 0) {
                player.seekTo(positionMs.coerceIn(0, duration))
            }
        }

        fun stopPlayback() = stopPlaybackInternal()
    }

    override fun onCreate() {
        super.onCreate()
        player = MediaPlayer()
        player.setAudioAttributes(audioAttributes)
        player.setOnCompletionListener {
            // 播放完自动播放下一首
            if (songs.isNotEmpty()) binder.playNext()
        }
        player.setOnErrorListener { _, what, extra ->
            Log.e(TAG, "MediaPlayer error: what=$what extra=$extra")
            isPrepared = false
            true
        }
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 通知栏按钮通过 PendingIntent.getService 回调到这里
        when (intent?.action) {
            ACTION_PREVIOUS -> binder.playPrevious()
            ACTION_TOGGLE -> binder.togglePlayPause()
            ACTION_NEXT -> binder.playNext()
        }
        // 通知栏按钮触发的启动若没有歌曲可播（例如服务被系统重建），直接结束
        if (intent?.action != null && songs.isEmpty()) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isPrepared = false
        runCatching { player.release() }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 播放控制

    private fun startSong(index: Int) {
        val song = songs.getOrNull(index) ?: return
        currentIndex = index
        isPrepared = false

        try {
            player.reset()
            player.setAudioAttributes(audioAttributes)
            player.setDataSource(this, song.uri)
            player.setOnPreparedListener { mp ->
                isPrepared = true
                mp.start()
                ensureForeground()
                refreshNotification()
            }
            player.prepareAsync()

            // 切歌时立刻把新歌名显示到通知栏
            ensureForeground()
        } catch (e: Exception) {
            Log.e(TAG, "startSong failed: ${song.title}", e)
            isPrepared = false
        }
    }

    private fun stopPlaybackInternal() {
        isPrepared = false
        runCatching { player.stop() }
        runCatching { player.reset() }

        if (isForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
        notificationManager.cancel(NOTIFICATION_ID)
        // 只结束“启动态”，Activity 如果还绑着服务不会立刻销毁
        stopSelf()
    }

    // ------------------------------------------------------------- Notification

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setSound(null, null)
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun ensureForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        isForeground = true
    }

    private fun refreshNotification() {
        if (isForeground) {
            // 对同一个 id 再次 startForeground 就是更新通知
            ensureForeground()
        }
    }

    private fun buildNotification(): Notification {
        val song = songs.getOrNull(currentIndex)
        val playing = isPrepared && player.isPlaying

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        fun actionIntent(action: String, requestCode: Int): PendingIntent =
            PendingIntent.getService(
                this,
                requestCode,
                Intent(this, MusicService::class.java).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_note)
            .setContentTitle(song?.title ?: getString(R.string.app_name))
            .setContentText(song?.artist ?: "")
            .setContentIntent(contentIntent)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(playing)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(R.drawable.ic_previous, getString(R.string.previous), actionIntent(ACTION_PREVIOUS, 1))
            .addAction(
                if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                getString(if (playing) R.string.pause else R.string.play),
                actionIntent(ACTION_TOGGLE, 2)
            )
            .addAction(R.drawable.ic_next, getString(R.string.next), actionIntent(ACTION_NEXT, 3))
            .setStyle(
                MediaStyle()
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }
}

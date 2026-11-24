package cn.edu.sicnu.cs.sjh.seventh_work

import android.Manifest
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var adapter: SongAdapter
    private lateinit var tvStatus: TextView
    private lateinit var tvNowPlaying: TextView
    private lateinit var tvCurrentTime: TextView
    private lateinit var tvTotalTime: TextView
    private lateinit var sbProgress: SeekBar
    private lateinit var btnPlayPause: Button

    private var songs: List<Song> = emptyList()

    @Volatile
    private var binder: MusicService.MusicBinder? = null
    private var bound = false

    @Volatile
    private var polling = false
    private var progressThread: Thread? = null
    private var userSeeking = false

    private val audioPermission: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (ContextCompat.checkSelfPermission(this, audioPermission) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                loadSongs()
            } else {
                tvStatus.setText(R.string.permission_denied)
                adapter.submitList(emptyList())
            }
        }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binder = service as? MusicService.MusicBinder
            // 把 Activity 查到的歌曲列表交给服务
            binder?.setSongs(songs)
            adapter.setPlayingIndex(binder?.getCurrentIndex() ?: -1)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binder = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        tvStatus = findViewById(R.id.tvStatus)
        tvNowPlaying = findViewById(R.id.tvNowPlaying)
        tvCurrentTime = findViewById(R.id.tvCurrentTime)
        tvTotalTime = findViewById(R.id.tvTotalTime)
        sbProgress = findViewById(R.id.sbProgress)
        btnPlayPause = findViewById(R.id.btnPlayPause)

        adapter = SongAdapter { position ->
            val b = binder
            if (b == null) {
                toast(R.string.service_not_connected)
            } else {
                ensureServiceStarted()
                b.playSong(position)
            }
        }

        findViewById<RecyclerView>(R.id.rvSongs).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }

        findViewById<Button>(R.id.btnPrevious).setOnClickListener {
            ensureServiceStarted()
            binder?.playPrevious()
        }
        btnPlayPause.setOnClickListener {
            val b = binder
            when {
                b == null -> toast(R.string.service_not_connected)
                b.getSongs().isEmpty() -> toast(R.string.no_music_found)
                else -> {
                    ensureServiceStarted()
                    b.togglePlayPause()
                }
            }
        }
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            binder?.stopPlayback()
        }
        findViewById<Button>(R.id.btnNext).setOnClickListener {
            ensureServiceStarted()
            binder?.playNext()
        }

        sbProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    tvCurrentTime.text = formatTime(progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                userSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                userSeeking = false
                binder?.seekTo(seekBar?.progress ?: 0)
            }
        })

        checkAndRequestPermission()
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, MusicService::class.java)
        // 让服务进入“启动”状态，这样 Activity 退到后台后音乐仍能继续播放
        runCatching { startService(intent) }
        bound = bindService(intent, connection, Context.BIND_AUTO_CREATE)
        startProgressLoop()
    }

    override fun onStop() {
        polling = false
        progressThread?.interrupt()
        progressThread = null
        if (bound) {
            unbindService(connection)
            bound = false
        }
        binder = null
        super.onStop()
    }

    // ------------------------------------------------------------ 权限与歌曲列表

    private fun checkAndRequestPermission() {
        val missing = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, audioPermission) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            missing.add(audioPermission)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (missing.isEmpty()) {
            loadSongs()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    /** 用 ContentResolver 从 MediaStore 读取音乐文件列表。 */
    private fun loadSongs() {
        if (ContextCompat.checkSelfPermission(this, audioPermission) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            tvStatus.setText(R.string.permission_denied)
            return
        }

        val result = mutableListOf<Song>()
        val collection: Uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        try {
            contentResolver.query(collection, projection, selection, null, sortOrder)?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    result.add(
                        Song(
                            id = id,
                            title = cleanMediaString(cursor.getString(titleColumn))
                                ?: getString(R.string.unknown_title),
                            artist = cleanMediaString(cursor.getString(artistColumn))
                                ?: getString(R.string.unknown_artist),
                            durationMs = cursor.getLong(durationColumn),
                            uri = ContentUris.withAppendedId(collection, id)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "读取音乐列表失败", e)
        }

        songs = result
        adapter.submitList(songs)
        binder?.setSongs(songs)

        tvStatus.text = if (songs.isEmpty()) {
            getString(R.string.no_music_found)
        } else {
            getString(R.string.songs_count, songs.size)
        }
    }

    /** MediaStore 有时会返回字面量 "<unknown>"，这里统一当作空值处理。 */
    private fun cleanMediaString(value: String?): String? =
        value?.takeIf { it.isNotBlank() && it != "<unknown>" }

    // ------------------------------------------------------------------ 进度刷新

    /** 后台线程不断读取服务中的进度，再回到主线程刷新 SeekBar。 */
    private fun startProgressLoop() {
        if (progressThread != null) return
        polling = true
        progressThread = Thread {
            while (polling) {
                val b = binder
                if (b != null) {
                    val song = b.getCurrentSong()
                    val prepared = b.isPrepared()
                    val playing = b.isPlaying()
                    val position = b.getPosition()
                    val duration = b.getDuration()
                    val index = b.getCurrentIndex()
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            applyPlayerState(song, prepared, playing, position, duration, index)
                        }
                    }
                }
                try {
                    Thread.sleep(300)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    private fun applyPlayerState(
        song: Song?,
        prepared: Boolean,
        playing: Boolean,
        position: Int,
        duration: Int,
        index: Int
    ) {
        tvNowPlaying.text = when {
            song == null -> getString(R.string.no_song_selected)
            playing -> getString(R.string.now_playing_format, song.title, song.artist)
            prepared -> getString(R.string.now_paused_format, song.title, song.artist)
            else -> getString(R.string.now_stopped_format, song.title, song.artist)
        }
        btnPlayPause.setText(if (playing) R.string.pause else R.string.play)
        adapter.setPlayingIndex(index)

        tvTotalTime.text = formatTime(duration)
        if (!userSeeking) {
            if (sbProgress.max != duration) {
                sbProgress.max = duration
            }
            sbProgress.progress = position.coerceIn(0, duration)
            tvCurrentTime.text = formatTime(position)
        }
    }

    /**
     * 停止播放时服务会调用 stopSelf() 清掉“启动态”。
     * 再次播放前重新 startService，保证按 Home 键退到后台后音乐不被销毁。
     */
    private fun ensureServiceStarted() {
        runCatching { startService(Intent(this, MusicService::class.java)) }
    }

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }
}

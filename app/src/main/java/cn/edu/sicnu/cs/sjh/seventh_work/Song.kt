package cn.edu.sicnu.cs.sjh.seventh_work

import android.net.Uri

/**
 * 一首本地音乐的信息，数据来自 MediaStore。
 */
data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val uri: Uri
)

package cn.edu.sicnu.cs.sjh.seventh_work

/** 把毫秒格式化为 mm:ss。 */
internal fun formatTime(ms: Int): String {
    if (ms <= 0) return "00:00"
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}

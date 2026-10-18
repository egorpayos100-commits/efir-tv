package plus.efir.tv.player

/** 1:07:32 above an hour, 7:32 below it — the remote has no seek bar, so times are shown as text. */
fun formatTimecode(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = total % 3600 / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

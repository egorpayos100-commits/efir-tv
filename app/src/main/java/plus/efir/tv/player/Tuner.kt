package plus.efir.tv.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import plus.efir.tv.CrashReport
import plus.efir.tv.data.Channel
import plus.efir.tv.data.Kind
import plus.efir.tv.data.Source

/**
 * One ExoPlayer instance shared by the preview pane and fullscreen:
 * the box only has a single hardware decoder.
 */
class Tuner(context: Context) {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    val player: ExoPlayer = ExoPlayer.Builder(appContext).build()

    var channel: Channel? = null
        private set
    var sourceIndex: Int = 0
        private set
    var error: String? = null
        private set
    var buffering: Boolean = false
        private set

    /** Where the next prepared item seeks to; films resume, live edges ignore it. */
    private var startPosition = 0L

    /** Fires on the main thread whenever the visible state changed. */
    var onState: (() -> Unit)? = null

    /** Human readable zaps: a source died and the next one is being tried. */
    var onEvent: ((title: String, message: String) -> Unit)? = null

    private val listener = object : Player.Listener {
        override fun onPlayerError(e: PlaybackException) {
            error = e.errorCodeName
            val total = channel?.sources?.size ?: 0
            if (sourceIndex + 1 < total) {
                sourceIndex += 1
                prepareCurrentSource()
                publish()
                onEvent?.invoke(
                    "Источник упал",
                    "${channel?.name}: пробуем ${channel?.sources?.get(sourceIndex)?.host()} · $total всего"
                )
            } else {
                publish()
                onEvent?.invoke(
                    "Нет сигнала",
                    "${channel?.name}: ${e.errorCodeName}. Все $total источника перепробованы."
                )
            }
        }

        override fun onIsLoadingChanged(isLoading: Boolean) {
            buffering = isLoading
            publish()
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_IDLE) error = null
            publish()
        }
    }

    init {
        player.addListener(listener)
    }

    fun tune(target: Channel, startAt: Long = 0) {
        startPosition = if (startAt > MIN_RESUME_MS) startAt else 0
        if (target.sources.isEmpty()) {
            stop()
            channel = target
            error = "нет источника"
            publish()
            return
        }
        channel = target
        sourceIndex = 0
        error = null
        prepareCurrentSource()
        publish()
    }

    /** Jumps by a remote-sized step and reports where it landed, or null if the length is unknown. */
    fun seekBy(deltaMs: Long): Long? {
        val duration = durationMs
        if (duration == 0L) return null
        val to = (positionMs + deltaMs).coerceIn(0, duration)
        player.seekTo(to)
        publish()
        return to
    }

    /** Length of the current item, or 0 while the container has not reported it yet. */
    val durationMs: Long
        get() = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0

    val positionMs: Long
        get() = player.currentPosition.coerceAtLeast(0)

    /** Nothing is loaded — after the app was backgrounded, ОК has to start the item again. */
    val isIdle: Boolean
        get() = player.playbackState == Player.STATE_IDLE

    /** The film ran to the end, so its saved position must be dropped. */
    val isFinished: Boolean
        get() = player.playbackState == Player.STATE_ENDED

    fun stop() {
        player.stop()
        player.clearMediaItems()
        buffering = false
    }

    fun release() {
        player.removeListener(listener)
        player.release()
    }

    val statusLine: String
        get() {
            val c = channel ?: return "—"
            val total = c.sources.size
            return when {
                total == 0 -> "нет источника"
                error != null -> "источник ${sourceIndex + 1}/$total · нет сигнала ($error)"
                buffering -> "настраиваемся…"
                c.kind == Kind.Film -> {
                    val watched = formatTimecode(positionMs)
                    val length = durationMs
                    if (length > 0) "$watched / ${formatTimecode(length)} · ${c.group}" else "$watched · ${c.group}"
                }
                else -> "источник ${sourceIndex + 1}/$total · ${c.group}"
            }
        }

    private fun prepareCurrentSource() {
        val source = channel?.sources?.getOrNull(sourceIndex) ?: return
        val factory = DefaultHttpDataSource.Factory()
        factory.setUserAgent(source.userAgent ?: DEFAULT_USER_AGENT)
        factory.setAllowCrossProtocolRedirects(true)
        source.referrer?.let { factory.setDefaultRequestProperties(mapOf("Referer" to it)) }

        val uri = Uri.parse(source.url)
        val mediaItem = MediaItem.fromUri(uri)
        // Own library files arrive as .mp4/.mkv/.ts; only .m3u8 needs the HLS reader.
        val mediaSource: MediaSource = if (uri.path.orEmpty().endsWith(".m3u8")) {
            HlsMediaSource.Factory(factory).createMediaSource(mediaItem)
        } else {
            ProgressiveMediaSource.Factory(factory).createMediaSource(mediaItem)
        }

        CrashReport.lastAction = "${channel?.name} · ${source.url.take(60)}"
        player.setMediaSource(mediaSource)
        player.prepare()
        if (startPosition > 0) player.seekTo(startPosition)
        player.playWhenReady = true
    }

    private fun publish() {
        if (Looper.myLooper() == Looper.getMainLooper()) onState?.invoke()
        else main.post { onState?.invoke() }
    }

    private fun Source.host(): String =
        runCatching { Uri.parse(url).host }.getOrNull() ?: url

    companion object {
        /** Under this the film is treated as never started, so a mis-click is not sticky. */
        const val MIN_RESUME_MS = 15_000L

        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
    }
}

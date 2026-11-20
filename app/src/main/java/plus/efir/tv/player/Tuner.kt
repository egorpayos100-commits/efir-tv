package plus.efir.tv.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import plus.efir.tv.CrashReport
import plus.efir.tv.data.Channel
import plus.efir.tv.data.Kind
import plus.efir.tv.data.Resolved
import plus.efir.tv.data.Resolvers
import plus.efir.tv.data.Source
import java.util.concurrent.Executors

/**
 * One ExoPlayer instance shared by the preview pane and fullscreen:
 * the box only has a single hardware decoder.
 */
class Tuner(context: Context) {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    private val selector = DefaultTrackSelector(appContext).apply {
        // Feeds out of India and Poland carry their own language first, so the player has to be
        // told to reach for Russian whenever the stream actually has one.
        parameters = parameters.buildUpon()
            .setPreferredAudioLanguage("rus")
            .setPreferredTextLanguage("rus")
            .build()
    }

    /** Общая поправка громкости: одна на эфир и на фильмы, живёт, пока живёт плеер. */
    private val gainStage = GainProcessor()

    private val renderers = object : DefaultRenderersFactory(appContext) {
        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean
        ): AudioSink = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessorChain(DefaultAudioSink.DefaultAudioProcessorChain(gainStage))
            .build()
    }

    val player: ExoPlayer = ExoPlayer.Builder(appContext, renderers)
        .setTrackSelector(selector)
        .setLoadControl(
            // Live playlists die on a hungry buffer: ask for more ahead of the play head.
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    MIN_BUFFER_MS, MAX_BUFFER_MS, PLAYBACK_BUFFER_MS, REBUFFER_MS
                )
                .build()
        )
        .build()

    var channel: Channel? = null
        private set
    var sourceIndex: Int = 0
        private set
    var error: String? = null
        private set
    var buffering: Boolean = false
        private set

    /** What the decoder was actually handed: size plus codec, read off the selected track. */
    var videoFormat: String? = null
        private set

    /** Rendition ceiling after a decoder refusal; 0 means the playlist picks its own top quality. */
    private var capHeight = 0

    /** Attempts spent on the address currently loaded. */
    private var attempts = 0

    /** Set once the whole table failed and a fresh playlist was asked for. */
    private var awaitingFresh = false

    /** Address taken from a RESOLVER source, valid until the next `tune`. */
    private var resolved: Source? = null

    /** The resolver already handed out a fresh address once for the current channel. */
    private var reResolved = false

    /** Bumped on every channel change so a late resolve result lands nowhere. */
    private var session = 0

    /** Where the next prepared item seeks to; films resume, live edges ignore it. */
    private var startPosition = 0L

    /** The screen is gone: nothing may touch the player again, not even a late resolve result. */
    private var released = false

    /** One worker for resolver calls: a retry chain used to spawn a thread per attempt. */
    private val resolvePool = Executors.newSingleThreadExecutor { task ->
        Thread(task, "resolver").apply { isDaemon = true }
    }

    private val retryRunnable = Runnable { if (channel != null) prepareCurrentSource() }

    /** A resolve call is in flight: the play head stands still for a reason, not because of the CDN. */
    private var resolving = false

    /** Where the play head last moved, for the stall watchdog. */
    private var lastProgress = -1L

    @Volatile
    private var lastProgressAt = 0L

    /**
     * His own fix for a frozen picture: zap away and straight back, and the stream restarts. That is
     * a re-prepare of the SAME address, and the watchdog is allowed this many of them before it
     * starts walking the rest of the row — one was too few for an edge that hiccups twice a minute.
     */
    private var stallRestarts = 0

    /**
     * A starved buffer is not an error: the player simply waits, so nothing rotates and he watches
     * one frozen frame for minutes — which is how both slow-edge complaints looked. Watch the play
     * head instead, since the download is already happening: if it does not move for STALL_MS while
     * the player is asked to play, this address is too slow for his network and gets treated exactly
     * like a dead one.
     */
    private val stallCheck = object : Runnable {
        override fun run() {
            if (released) return
            val target = channel
            if (target != null) main.postDelayed(this, STALL_TICK_MS)
            if (target == null || target.kind == Kind.Film) return
            val position = positionMs
            if (position > lastProgress) {
                lastProgress = position
                lastProgressAt = System.currentTimeMillis()
            }
            val waiting = player.playWhenReady && !resolving &&
                player.playbackState != Player.STATE_ENDED &&
                player.playbackState != Player.STATE_IDLE
            if (!waiting || System.currentTimeMillis() - lastProgressAt < STALL_MS) return
            lastProgressAt = System.currentTimeMillis()
            error = "поток не успевает"
            PlaybackLog.recordStall(target.name, currentUrl())
            if (stallRestarts < SAME_ADDRESS_RESTARTS) {
                // First freezes: the same address gets the restart he used to do by hand. His Animal
                // Planet complaint was this watchdog walking off a working edge onto another channel's
                // language, so the manual gesture gets the room it actually needs.
                stallRestarts += 1
                onEvent?.invoke(
                    "Картинка встала",
                    "${target.name}: без движения ${STALL_MS / 1000} с — перезапускаю этот же адрес " +
                        "($stallRestarts/$SAME_ADDRESS_RESTARTS)"
                )
                uiCancelRetry()
                prepareCurrentSource()
                publish()
                return
            }
            onEvent?.invoke(
                "Картинка встала",
                "${target.name}: без движения ${STALL_MS / 1000} с — беру следующий адрес"
            )
            advanceAfterFailure(target)
        }
    }

    /** Fires on the main thread whenever the visible state changed. */
    var onState: (() -> Unit)? = null

    /** Human readable zaps: a source died and the next one is being tried. */
    var onEvent: ((title: String, message: String) -> Unit)? = null

    /** Every address of this channel failed: the screen should reload the playlist and try again. */
    var onExhausted: ((Channel) -> Unit)? = null

    /** A fresh playlist did not help either: the screen may look for the channel somewhere else. */
    var onGaveUp: ((Channel) -> Unit)? = null

    private val listener = object : Player.Listener {
        override fun onPlayerError(e: PlaybackException) {
            val dead = channel ?: return
            val source = dead.sources.getOrNull(sourceIndex)
            PlaybackLog.record(dead, currentUrl(), e)
            CrashReport.lastAction = "${dead.name} · ${e.errorCodeName}"
            uiCancelRetry()

            val decodeCap = if (e.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED) nextCap() else null
            error = e.errorCodeName

            when {
                // The decoder said no: the same address, one quality down, is worth one try.
                decodeCap != null -> {
                    applyCap(decodeCap)
                    retryAfter(0)
                    onEvent?.invoke(
                        "Декодер отказал",
                        "${dead.name}: $error — пробуем качество ≤ ${decodeCap}p"
                    )
                }

                // A resolved address is the cheapest thing to be wrong: ask the channel for a new
                // one before blaming the stream itself.
                source?.isResolver == true && !reResolved -> {
                    reResolved = true
                    resolved = null
                    Resolvers.forget(appContext, source.resolver.orEmpty())
                    onEvent?.invoke("Обновляю ссылку", "${dead.name}: запрошу её заново у канала")
                    retryAfter(0)
                }

                // A dropped Wi-Fi hop is not a dead channel: same address, a few times over.
                attempts + 1 < MAX_ATTEMPTS -> {
                    attempts += 1
                    retryAfter(RETRY_DELAY_MS)
                    onEvent?.invoke(
                        "Переподключение",
                        "${dead.name}: $error — попытка ${attempts + 1} из $MAX_ATTEMPTS"
                    )
                }

                else -> advanceAfterFailure(dead)
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            val group = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.isSelected }
            videoFormat = group?.getTrackFormat(0)?.let { f ->
                val codec = f.codecs?.takeIf { it.isNotBlank() }
                    ?: f.sampleMimeType?.substringAfter('/') ?: "?"
                "${f.width}×${f.height} · $codec"
            }
            publish()
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
        uiCancelRetry()
        session++
        gainStage.enabled = target.kind == Kind.Film
        gainStage.trimDb = if (target.kind == Kind.Film) target.gainDb else 0f
        startPosition = if (startAt > MIN_RESUME_MS) startAt else 0
        applyCap(0)
        videoFormat = null
        attempts = 0
        awaitingFresh = false
        resolved = null
        reResolved = false
        if (target.sources.isEmpty()) {
            stop()
            channel = target
            error = "нет источника"
            publish()
            return
        }
        channel = target
        sourceIndex = 0
        stallRestarts = 0
        error = null
        prepareCurrentSource()
        startStallCheck()
        publish()
    }

    /**
     * Second chance after a playlist reload: the same channel, addresses taken from the fresh list.
     * Returns false when the new list has nothing for it either.
     */
    fun retryWith(target: Channel): Boolean {
        awaitingFresh = false
        if (target.sources.isEmpty()) return false
        gainStage.enabled = target.kind == Kind.Film
        gainStage.trimDb = if (target.kind == Kind.Film) target.gainDb else 0f
        session++
        attempts = 0
        sourceIndex = 0
        stallRestarts = 0
        error = null
        resolved = null
        reResolved = false
        channel = target
        prepareCurrentSource()
        startStallCheck()
        publish()
        return true
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

    /** Picture is really on the way: this is what keeps the TV awake. */
    val isPlaying: Boolean
        get() = player.isPlaying

    /** The film ran to the end, so its saved position must be dropped. */
    val isFinished: Boolean
        get() = player.playbackState == Player.STATE_ENDED

    fun stop() {
        uiCancelRetry()
        main.removeCallbacks(stallCheck)
        player.stop()
        player.clearMediaItems()
        buffering = false
    }

    /**
     * The picture is handed to another player (the WebView): the decoder is let go and no row is
     * remembered, so the guide strip and the «playing» mark do not stay on a channel that is off.
     */
    fun clear() {
        stop()
        channel = null
        error = null
        videoFormat = null
        publish()
    }

    fun release() {
        uiCancelRetry()
        main.removeCallbacks(stallCheck)
        released = true
        resolvePool.shutdownNow()
        player.removeListener(listener)
        player.release()
    }

    /** The address on air right now, for the info screen. A resolver row reports what it resolved. */
    fun currentUrl(): String? =
        channel?.sources?.getOrNull(sourceIndex)?.takeIf { !it.isResolver }?.url ?: resolved?.url

    val statusLine: String
        get() {
            val c = channel ?: return "—"
            val total = c.sources.size
            val picture = videoFormat?.let { " · $it" } ?: ""
            return when {
                total == 0 -> "нет источника"
                error != null -> "источник ${sourceIndex + 1}/$total · нет сигнала ($error$picture)"
                buffering -> "настраиваемся…"
                c.kind == Kind.Film -> {
                    val watched = formatTimecode(positionMs)
                    val length = durationMs
                    if (length > 0) "$watched / ${formatTimecode(length)} · ${c.group}" else "$watched · ${c.group}"
                }
                else -> "источник ${sourceIndex + 1}/$total · ${c.group}$picture"
            }
        }

    /** Next ceiling down the ladder, or null once 720p was already refused. */
    private fun nextCap(): Int? {
        val from = if (capHeight == 0) Int.MAX_VALUE else capHeight
        return DECODER_CAPS.firstOrNull { it < from }
    }

    private fun applyCap(height: Int) {
        if (capHeight == height) return
        capHeight = height
        val parameters = selector.buildUponParameters()
        if (height == 0) parameters.clearVideoSizeConstraints()
        else parameters.setMaxVideoSize(Int.MAX_VALUE, height)
        selector.parameters = parameters.build()
    }

    private fun retryAfter(delayMs: Long) {
        uiCancelRetry()
        if (delayMs == 0L) prepareCurrentSource() else main.postDelayed(retryRunnable, delayMs)
        publish()
    }

    private fun uiCancelRetry() = main.removeCallbacks(retryRunnable)

    /** Re-arms the stall watchdog for a freshly started address. */
    private fun startStallCheck() {
        main.removeCallbacks(stallCheck)
        lastProgress = -1L
        lastProgressAt = System.currentTimeMillis()
        main.postDelayed(stallCheck, STALL_TICK_MS)
    }

    private fun prepareCurrentSource() {
        if (released) return
        // A new item starts its own clock: the live play head jumps back to the edge, so the position
        // remembered from the previous item would look like a freeze that never ends. Without this
        // re-arm one hiccup makes the watchdog restart, then walk the whole row, forever.
        startStallCheck()
        val source = channel?.sources?.getOrNull(sourceIndex) ?: return
        if (source.isResolver) {
            val taken = resolved
            when {
                // The address this channel already got is still the one to play.
                taken != null -> prepare(taken)
                else -> resolve(source, force = reResolved)
            }
            return
        }
        prepare(source)
    }

    /**
     * A RESOLVER row has no address in the playlist, so the broadcaster's own player is asked for
     * one. The call is network work and never happens on the UI thread; the result is posted back
     * and dropped if the viewer already zapped away.
     */
    private fun resolve(source: Source, force: Boolean) {
        val id = source.resolver ?: return
        val generation = session
        val target = channel ?: return
        resolving = true
        buffering = true
        error = null
        publish()
        resolvePool.execute {
            val outcome = try {
                Resolvers.resolve(appContext, id, force)
            } catch (e: Exception) {
                Resolved.Failed("резолвер упал: ${e.javaClass.simpleName}")
            }
            main.post {
                resolving = false
                if (released || generation != session || channel?.key != target.key) return@post
                when (outcome) {
                    is Resolved.Stream -> {
                        resolved = Source(outcome.url, outcome.headers)
                        PlaybackLog.recordResolve(target.name, outcome.url, "ссылка получена")
                        prepare(resolved!!)
                    }
                    is Resolved.Blocked -> {
                        resolved = null
                        failResolve(target, outcome.reason, blocked = true)
                    }
                    is Resolved.Failed -> {
                        resolved = null
                        failResolve(target, outcome.reason, blocked = false)
                    }
                }
            }
        }
    }

    /**
     * The screen found that the address on air is the broadcaster's promo advert. Nothing inside the
     * player can notice: a slate has valid segments and a moving edge, so it never errors and never
     * rotates. The probe's verdict decides instead — jump to the first address of the row the screen
     * still trusts, or fall into the ordinary chain when none of them is left.
     */
    fun abandonSlate(playable: (Source) -> Boolean, reason: String) {
        val dead = channel ?: return
        if (released || dead.sources.isEmpty()) return
        val onAir = dead.sources.getOrNull(sourceIndex)
        if (onAir != null && !onAir.isResolver && onAir.url.isNotEmpty() && playable(onAir)) return
        uiCancelRetry()
        val next = dead.sources.indices.firstOrNull { index ->
            val source = dead.sources[index]
            index != sourceIndex && (source.isResolver || (source.url.isNotEmpty() && playable(source)))
        }
        attempts = 0
        if (next == null) {
            error = reason
            advanceAfterFailure(dead)
            return
        }
        sourceIndex = next
        stallRestarts = 0
        error = null
        retryAfter(0)
        onEvent?.invoke(
            "Рекламная заглушка",
            "${dead.name}: убрали заглушку, играю ${dead.sources[next].describe()} · ${next + 1}/${dead.sources.size}"
        )
    }

    /** Rest of the chain: next address of this channel, then a fresh playlist, then give up. */
    private fun advanceAfterFailure(dead: Channel) {
        val total = dead.sources.size
        when {
            sourceIndex + 1 < total -> {
                sourceIndex += 1
                attempts = 0
                stallRestarts = 0
                retryAfter(0)
                onEvent?.invoke(
                    "Источник упал",
                    "${dead.name}: пробуем ${dead.sources[sourceIndex].host()} · ${sourceIndex + 1}/$total"
                )
            }

            // Nothing in the table works: the list itself is what has gone stale.
            !awaitingFresh -> {
                awaitingFresh = true
                publish()
                onEvent?.invoke("Обновляю плейлист", "${dead.name}: ищу свежие адреса")
                onExhausted?.invoke(dead)
            }

            else -> {
                awaitingFresh = false
                publish()
                onEvent?.invoke(
                    "Нет сигнала",
                    "${dead.name}: $error. $total источника перепробованы, свежий плейлист не помог."
                )
                onGaveUp?.invoke(dead)
            }
        }
    }

    /** The resolver gave nothing playable: log why, then fall through to the rest of the chain. */
    private fun failResolve(target: Channel, reason: String, blocked: Boolean) {
        error = reason
        PlaybackLog.recordResolve(target.name, null, reason)
        buffering = false
        onEvent?.invoke(if (blocked) "Недоступно" else "Резолвер молчит", "${target.name}: $reason")
        advanceAfterFailure(target)
    }

    private fun prepare(source: Source) {
        // A retry that gets to load is no longer a failure, whatever the line said a second ago.
        error = null
        val factory = DefaultHttpDataSource.Factory()
        factory.setUserAgent(source.headers["User-Agent"] ?: DEFAULT_USER_AGENT)
        factory.setAllowCrossProtocolRedirects(true)
        factory.setConnectTimeoutMs(CONNECT_TIMEOUT_MS)
        factory.setReadTimeoutMs(READ_TIMEOUT_MS)
        // Headers typed into the playlist (referer, cookie, token) go on every request, segments too.
        val extra = source.headers.filterKeys { !it.equals("User-Agent", true) }
        if (extra.isNotEmpty()) factory.setDefaultRequestProperties(extra)

        val uri = Uri.parse(source.url)
        val stream = streamOf(source.url)
        val item = MediaItem.Builder().setUri(uri).setMimeType(stream.mime)
        if (stream == Stream.Hls) {
            // У части HLS-источников окно живого эфира всего 3–4 сегмента (~22 с), а плеер по
            // умолчанию стартует в три сегмента от края: любой подгруз на таком потоке уводит
            // позицию за начало окна и превращается в BehindLiveWindowException по кругу.
            item.setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setTargetOffsetMs(LIVE_EDGE_MS)
                    .setMinPlaybackSpeed(1f)
                    .setMaxPlaybackSpeed(LIVE_CATCH_UP_SPEED)
                    .build()
            )
        }
        val mediaItem = item.build()
        val mediaSource: MediaSource = when (streamOf(source.url)) {
            Stream.Hls -> HlsMediaSource.Factory(factory).createMediaSource(mediaItem)
            Stream.Dash -> DashMediaSource.Factory(factory).createMediaSource(mediaItem)
            // .ts / .mp4 / .mkv and bare stream addresses: let the extractors sniff the container.
            Stream.Progressive -> ProgressiveMediaSource.Factory(factory).createMediaSource(mediaItem)
        }

        CrashReport.lastAction = "${channel?.name} · ${source.url.take(60)}"
        Log.i(TAG, "tune ${channel?.name} → ${source.url.take(120)}")
        player.setMediaSource(mediaSource)
        player.prepare()
        if (startPosition > 0) player.seekTo(startPosition)
        player.playWhenReady = true
    }

    private enum class Stream(val mime: String?) {
        Hls(MimeTypes.APPLICATION_M3U8),
        Dash(MimeTypes.APPLICATION_MPD),
        Progressive(null),
    }

    /**
     * The address decides the reader, and it hides in three shapes: an extension on the path,
     * an extension behind a query string, or nothing at all (raw TS from the broadcaster).
     */
    private fun streamOf(url: String): Stream {
        val lower = url.lowercase()
        val path = lower.substringBefore('?')
        return when {
            path.endsWith(".m3u8") || path.endsWith(".m3u") || lower.contains(".m3u8") -> Stream.Hls
            path.endsWith(".mpd") || lower.contains(".mpd") -> Stream.Dash
            else -> Stream.Progressive
        }
    }

    private fun publish() {
        if (Looper.myLooper() == Looper.getMainLooper()) onState?.invoke()
        else main.post { onState?.invoke() }
    }

    private fun Source.host(): String =
        runCatching { Uri.parse(url).host }.getOrNull() ?: url

    /** What to call a source in a line meant for him. */
    private fun Source.describe(): String =
        if (isResolver) "резолвер ${resolver.orEmpty()}" else host()

    companion object {
        private const val TAG = "EfirTuner"

        /** Under this the film is treated as never started, so a mis-click is not sticky. */
        const val MIN_RESUME_MS = 15_000L

        /** Frame-height ceilings tried after the hardware decoder refuses a rendition. */
        val DECODER_CAPS = intArrayOf(720, 576, 480)

        /** Tries on one address before the next source in the table is used. */
        private const val MAX_ATTEMPTS = 4
        private const val RETRY_DELAY_MS = 2_000L

        /**
         * How long the play head may stand still on a live channel before the address is replaced.
         * His 29.10 order: 30 с was switching links on every small hiccup and the TV became
         * unwatchable, so one address now gets a minute and a half of real patience per check.
         */
        private const val STALL_MS = 90_000L
        private const val STALL_TICK_MS = 5_000L

        /** Re-prepare of the SAME address allowed before the row starts being walked. */
        private const val SAME_ADDRESS_RESTARTS = 3
        private const val CONNECT_TIMEOUT_MS = 12_000
        private const val READ_TIMEOUT_MS = 20_000
        private const val MIN_BUFFER_MS = 15_000
        private const val MAX_BUFFER_MS = 60_000
        private const val PLAYBACK_BUFFER_MS = 5_000
        private const val REBUFFER_MS = 3_000
        private const val LIVE_EDGE_MS = 6_000L
        private const val LIVE_CATCH_UP_SPEED = 1.08f

        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
    }
}

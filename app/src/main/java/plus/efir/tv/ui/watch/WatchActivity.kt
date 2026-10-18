package plus.efir.tv.ui.watch

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.ui.PlayerView
import plus.efir.tv.EfirApp
import plus.efir.tv.R
import plus.efir.tv.data.Channel
import plus.efir.tv.data.Source

class WatchActivity : androidx.fragment.app.FragmentActivity() {

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var overlay: View
    private lateinit var ribbon: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var statusView: TextView
    private lateinit var toastView: TextView

    private val ui = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { setOverlay(false) }

    private var channels: List<Channel> = emptyList()
    private var index = 0
    private var sourceIndex = 0
    private var overlayVisible = true
    private var lastError: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_watch)

        playerView = findViewById(R.id.player_view)
        overlay = findViewById(R.id.watch_overlay)
        ribbon = findViewById(R.id.channel_ribbon)
        titleView = findViewById(R.id.watch_title)
        statusView = findViewById(R.id.watch_status)
        toastView = findViewById(R.id.watch_toast)

        channels = (applicationContext as EfirApp).repository.channels()
        val key = intent.getStringExtra(EXTRA_KEY)
        index = channels.indexOfFirst { it.key == key }.coerceAtLeast(0)

        if (channels.isEmpty()) {
            statusView.text = "Плейлист пуст: добавь assets/channels.m3u"
            return
        }

        player = ExoPlayer.Builder(this).build().also { playerView.player = it }
        tune(index, announce = false)
        showOverlayTemporarily()
    }

    override fun onDestroy() {
        ui.removeCallbacks(hideRunnable)
        player?.release()
        player = null
        super.onDestroy()
    }

    private fun tune(target: Int, announce: Boolean = true) {
        if (channels.isEmpty()) return
        index = ((target % channels.size) + channels.size) % channels.size
        sourceIndex = 0
        lastError = null
        playCurrentSource()
        renderChrome()
        if (announce) setOverlay(true)
        showOverlayTemporarily()
    }

    private fun playCurrentSource() {
        val channel = channels[index]
        val source = channel.sources.getOrNull(sourceIndex) ?: return
        val factory = DefaultHttpDataSource.Factory()
        factory.setUserAgent(source.userAgent ?: DEFAULT_USER_AGENT)
        factory.setAllowCrossProtocolRedirects(true)
        source.referrer?.let { factory.setDefaultRequestProperties(mapOf("Referer" to it)) }
        val mediaSource = HlsMediaSource.Factory(factory)
            .createMediaSource(MediaItem.fromUri(source.url))
        player?.apply {
            removeListener(errorListener)
            setMediaSource(mediaSource)
            prepare()
            playWhenReady = true
            addListener(errorListener)
        }
    }

    private val errorListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            val channel = channels.getOrNull(index) ?: return
            lastError = error.errorCodeName
            val hasNextSource = sourceIndex + 1 < channel.sources.size
            if (hasNextSource) {
                sourceIndex += 1
                player?.removeListener(this)
                playCurrentSource()
                renderChrome()
                toast("Источник упал", "Пробуем ${channel.sources[sourceIndex].url.host()} · ${channel.name}")
            } else {
                renderChrome()
                toast("Нет сигнала", "${channel.name}: $lastError. Все источники перепробованы.")
            }
        }
    }

    private fun renderChrome() {
        val channel = channels.getOrElse(index) { return }
        titleView.text = channel.name
        val total = channel.sources.size
        val state = when {
            total == 0 -> "нет источника"
            lastError != null -> "источник ${sourceIndex + 1}/$total · ошибка $lastError"
            else -> "источник ${sourceIndex + 1}/$total · ${channel.group}"
        }
        statusView.text = state
        buildRibbon(channel)
    }

    private fun buildRibbon(current: Channel) {
        ribbon.removeAllViews()
        val start = (channels.indexOf(current) - 8).coerceAtLeast(0)
        val end = (start + 17).coerceAtMost(channels.size)
        for (i in start until end) {
            val channel = channels[i]
            val chip = layoutInflater.inflate(R.layout.view_channel_chip, ribbon, false) as TextView
            chip.text = "${channel.num()}. ${channel.name}"
            chip.isSelected = channel.key == current.key
            chip.setOnClickListener { tune(i) }
            ribbon.addView(chip)
        }
    }

    private fun Channel.num() = channels.indexOf(this) + 1

    private fun setOverlay(visible: Boolean) {
        overlayVisible = visible
        overlay.alpha = if (visible) 1f else 0f
        overlay.visibility = if (visible) View.VISIBLE else View.INVISIBLE
    }

    private fun showOverlayTemporarily() {
        setOverlay(true)
        ui.removeCallbacks(hideRunnable)
        ui.postDelayed(hideRunnable, OVERLAY_TIMEOUT_MS)
    }

    private fun toast(title: String, message: String) {
        toastView.text = "$title · $message"
        toastView.visibility = View.VISIBLE
        ui.postDelayed({ toastView.visibility = View.GONE }, TOAST_MS)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (overlayVisible) setOverlay(false) else showOverlayTemporarily()
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (overlayVisible) {
                    setOverlay(false)
                    ui.removeCallbacks(hideRunnable)
                } else {
                    finish()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_DOWN -> {
                tune(index - 1); return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP -> {
                tune(index + 1); return true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                val dir = if (event.keyCode == KeyEvent.KEYCODE_MEDIA_NEXT) 1 else -1
                tune(index + dir); return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    companion object {
        private const val EXTRA_KEY = "channel_key"
        private const val OVERLAY_TIMEOUT_MS = 4000L
        private const val TOAST_MS = 3500L
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

        fun start(context: Context, key: String) {
            context.startActivity(Intent(context, WatchActivity::class.java).putExtra(EXTRA_KEY, key))
        }

        private fun String.host(): String = runCatching {
            java.net.URI(this).host ?: this
        }.getOrDefault(this)
    }
}

package plus.efir.tv.ui.home

import android.content.Context
import android.graphics.Outline
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.FragmentActivity
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import plus.efir.tv.EfirApp
import plus.efir.tv.R
import plus.efir.tv.data.Channel
import plus.efir.tv.data.EpgGuide
import plus.efir.tv.data.Programme
import plus.efir.tv.data.Slots
import plus.efir.tv.player.Tuner
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HomeActivity : FragmentActivity() {

    private enum class Mode { PREVIEW, FULLSCREEN }

    /** One line of the guide: start time + programme name. */
    private class Row(val root: View, val time: TextView, val title: TextView)

    private lateinit var root: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var browsePane: LinearLayout
    private lateinit var rail: RecyclerView
    private lateinit var channelList: RecyclerView
    private lateinit var paneTitle: TextView
    private lateinit var fullOverlay: View
    private lateinit var fullTitle: TextView
    private lateinit var fullStatus: TextView
    private lateinit var fullHint: View
    private lateinit var toastView: TextView
    private lateinit var epgPane: LinearLayout
    private lateinit var epgRows: List<Row>
    private lateinit var epgProgress: View
    private lateinit var epgProgressFill: View

    private lateinit var tuner: Tuner
    private lateinit var epg: EpgGuide
    private lateinit var railAdapter: RailAdapter
    private lateinit var channelAdapter: ChannelAdapter

    private var categories: List<Category> = emptyList()
    private var allChannels: List<Channel> = emptyList()
    private var epgIds: Set<String> = emptySet()
    private var categoryIndex = 0
    private var channelIndex = 0
    private var mode = Mode.PREVIEW

    private val ui = Handler(Looper.getMainLooper())
    private val clock = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val zapRunnable = Runnable { tuneNow() }
    private val hideRunnable = Runnable { setOverlay(false) }
    private val hideToastRunnable = Runnable { toastView.visibility = View.GONE }
    private val epgTicker = object : Runnable {
        override fun run() {
            refreshEpgPane()
            epg.refresh(epgIds)
            ui.postDelayed(this, EPG_TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        root = findViewById(R.id.root)
        playerView = findViewById(R.id.player_view)
        browsePane = findViewById(R.id.browse_pane)
        rail = findViewById(R.id.rail)
        channelList = findViewById(R.id.channel_list)
        paneTitle = findViewById(R.id.pane_title)
        fullOverlay = findViewById(R.id.full_overlay)
        fullTitle = findViewById(R.id.full_title)
        fullStatus = findViewById(R.id.full_status)
        fullHint = findViewById(R.id.full_hint)
        toastView = findViewById(R.id.toast)
        epgPane = findViewById(R.id.epg_pane)
        epgRows = listOf(
            Row(findViewById(R.id.epg_prev), findViewById(R.id.epg_prev_time), findViewById(R.id.epg_prev_title)),
            Row(findViewById(R.id.epg_now), findViewById(R.id.epg_now_time), findViewById(R.id.epg_now_title)),
            Row(findViewById(R.id.epg_next), findViewById(R.id.epg_next_time), findViewById(R.id.epg_next_title)),
        )
        epgProgress = findViewById(R.id.epg_progress)
        epgProgressFill = findViewById(R.id.epg_progress_fill)
        epgProgressFill.pivotX = 0f

        playerView.outlineProvider = RoundedOutline()
        playerView.clipToOutline = true

        tuner = Tuner(this)
        playerView.player = tuner.player
        tuner.onState = { renderStatus() }
        tuner.onEvent = { title, message -> toast(title, message) }

        epg = (applicationContext as EfirApp).epg
        epg.onLoaded = { refreshEpgPane() }

        buildCategories()

        rail.layoutManager = LinearLayoutManager(this)
        channelList.layoutManager = LinearLayoutManager(this)
        railAdapter = RailAdapter(onPicked = { showCategory(it) })
        channelAdapter = ChannelAdapter(
            onFocused = { channel -> onRowFocused(channel) },
            onOpened = { enterFullscreen() },
        )
        rail.adapter = railAdapter
        channelList.adapter = channelAdapter
        railAdapter.submit(categories)

        restoreLastChannel()
        showCategory(categoryIndex)

        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyGeometry() }
        setOverlay(false)
        fullHint.visibility = View.INVISIBLE

        channelList.requestFocus()
        channelAdapter.requestFocusAt(channelIndex)
        ui.post { tuneNow() }

        CrashActivity.startIfCrashed(this)
    }

    override fun onStart() {
        super.onStart()
        if (tuner.channel != null) tuneNow()
        ui.post(epgTicker)
    }

    override fun onStop() {
        super.onStop()
        ui.removeCallbacks(zapRunnable)
        ui.removeCallbacks(epgTicker)
        tuner.stop()
    }

    override fun onDestroy() {
        ui.removeCallbacks(zapRunnable)
        ui.removeCallbacks(hideRunnable)
        epg.onLoaded = null
        tuner.release()
        super.onDestroy()
    }

    // region selection

    private fun buildCategories() {
        val repo = (applicationContext as EfirApp).repository
        allChannels = repo.channels()
        epgIds = allChannels.mapNotNull { it.epgId }.toSet()
        val groups = repo.byGroup().map { (title, channels) -> Category(title, channels) }
        categories = if (allChannels.isEmpty()) emptyList()
        else listOf(Category(getString(R.string.all_channels), allChannels)) + groups
    }

    private fun restoreLastChannel() {
        val lastKey = prefs().getString(LAST_KEY, null)
        val last = allChannels.firstOrNull { it.key == lastKey } ?: allChannels.firstOrNull() ?: return
        categoryIndex = categoryOf(last)
        channelIndex = categories[categoryIndex].channels.indexOfFirst { it.key == last.key }.coerceAtLeast(0)
    }

    /** Prefer the real group over the "Все" catch-all so the list opens where the channel lives. */
    private fun categoryOf(channel: Channel): Int {
        val match = categories.withIndex().firstOrNull { (position, category) ->
            position > 0 && category.channels.any { it.key == channel.key }
        }
        return match?.index ?: 0
    }

    private fun onRowFocused(channel: Channel) {
        val position = categories.getOrNull(categoryIndex)?.channels
            ?.indexOfFirst { it.key == channel.key } ?: return
        if (position < 0) return
        channelIndex = position
        if (tuner.channel?.key == channel.key) return
        ui.removeCallbacks(zapRunnable)
        ui.postDelayed(zapRunnable, ZAP_DEBOUNCE_MS)
    }

    private fun tuneNow() {
        ui.removeCallbacks(zapRunnable)
        current()?.let { tuner.tune(it) }
        persistCurrent()
    }

    private fun current(): Channel? =
        categories.getOrNull(categoryIndex)?.channels?.getOrNull(channelIndex)

    private fun showCategory(index: Int) {
        if (categories.isEmpty()) {
            paneTitle.text = getString(R.string.empty_playlist)
            return
        }
        val keepKey = current()?.key
        categoryIndex = index.coerceIn(0, categories.lastIndex)
        val category = categories[categoryIndex]
        paneTitle.text = "${category.title} · ${category.channels.size}"
        channelIndex = category.channels.indexOfFirst { it.key == keepKey }.coerceAtLeast(0)
        channelAdapter.submit(category.channels)
    }

    /** Move the selection to an arbitrary channel, switching the visible category if needed. */
    private fun select(channel: Channel) {
        val target = categoryOf(channel)
        if (target != categoryIndex) showCategory(target)
        channelIndex = categories[target].channels.indexOfFirst { it.key == channel.key }
        channelAdapter.requestFocusAt(channelIndex)
        tuneNow()
    }

    private fun stepAll(delta: Int) {
        if (allChannels.isEmpty()) return
        val from = allChannels.indexOfFirst { it.key == current()?.key }.coerceAtLeast(0)
        val next = ((from + delta) % allChannels.size + allChannels.size) % allChannels.size
        select(allChannels[next])
    }

    private fun persistCurrent() {
        current()?.let { prefs().edit().putString(LAST_KEY, it.key).apply() }
    }

    // endregion

    // region geometry + chrome

    private fun applyGeometry() {
        val width = root.width
        val height = root.height
        if (width == 0 || height == 0 || mode == Mode.FULLSCREEN) return

        val gutter = dp(32f)
        val gap = dp(12f)
        val reserve = dp(GUIDE_RESERVE_DP)
        val usable = height - gutter * 2
        var previewWidth = (width * PREVIEW_SHARE).toInt()
        var previewHeight = (previewWidth * 9f / 16f).toInt()
        val maxHeight = usable - gap - reserve
        if (previewHeight > maxHeight) {
            previewHeight = maxHeight.coerceAtLeast(dp(140f))
            previewWidth = (previewHeight * 16f / 9f).toInt()
        }
        val top = gutter + ((usable - previewHeight - gap - reserve).coerceAtLeast(0) / 2)

        playerView.updateLayoutParams<FrameLayout.LayoutParams> {
            this.width = previewWidth
            this.height = previewHeight
            gravity = Gravity.END or Gravity.TOP
            leftMargin = 0
            rightMargin = gutter
            topMargin = top
            bottomMargin = 0
        }
        epgPane.updateLayoutParams<FrameLayout.LayoutParams> {
            this.width = previewWidth
            this.height = ViewGroup.LayoutParams.WRAP_CONTENT
            gravity = Gravity.END or Gravity.TOP
            leftMargin = 0
            rightMargin = gutter
            topMargin = top + previewHeight + gap
            bottomMargin = 0
        }
        browsePane.setPadding(gutter, gutter, previewWidth + gutter * 2, gutter)
    }

    /** In fullscreen the same panel becomes a lower-third over the picture. */
    private fun placeEpgOverPicture() {
        val gutter = dp(32f)
        epgPane.updateLayoutParams<FrameLayout.LayoutParams> {
            this.width = dp(GUIDE_WIDTH_DP)
            this.height = ViewGroup.LayoutParams.WRAP_CONTENT
            gravity = Gravity.START or Gravity.BOTTOM
            leftMargin = gutter
            topMargin = 0
            rightMargin = 0
            bottomMargin = gutter
        }
    }

    private fun enterFullscreen() {
        if (mode == Mode.FULLSCREEN || allChannels.isEmpty()) return
        ui.removeCallbacks(zapRunnable)
        if (tuner.channel?.key != current()?.key) tuneNow()
        mode = Mode.FULLSCREEN

        browsePane.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        browsePane.visibility = View.INVISIBLE
        playerView.updateLayoutParams<FrameLayout.LayoutParams> {
            width = FrameLayout.LayoutParams.MATCH_PARENT
            height = FrameLayout.LayoutParams.MATCH_PARENT
            gravity = Gravity.NO_GRAVITY
            rightMargin = 0
            topMargin = 0
        }
        placeEpgOverPicture()
        renderStatus()
        setOverlay(true)
    }

    private fun exitFullscreen() {
        if (mode != Mode.FULLSCREEN) return
        mode = Mode.PREVIEW
        setOverlay(false)
        browsePane.visibility = View.VISIBLE
        browsePane.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        applyGeometry()
        refreshEpgPane()
        channelList.requestFocus()
        channelAdapter.requestFocusAt(channelIndex)
    }

    private fun setOverlay(visible: Boolean) {
        fullOverlay.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        refreshEpgPane()
        ui.removeCallbacks(hideRunnable)
        if (visible) ui.postDelayed(hideRunnable, OVERLAY_TIMEOUT_MS)
    }

    private fun renderStatus() {
        val channel = tuner.channel ?: current() ?: run {
            fullTitle.text = getString(R.string.empty_playlist)
            fullStatus.text = ""
            refreshEpgPane()
            return
        }
        fullTitle.text = "${channel.number}. ${channel.name}"
        fullStatus.text = tuner.statusLine
        refreshEpgPane()
    }

    // region programme guide

    private fun refreshEpgPane() {
        val slots = epg.slots((tuner.channel ?: current())?.epgId, System.currentTimeMillis())
        if (slots.isEmpty) {
            epgPane.visibility = View.GONE
            return
        }
        paint(slots)
        val hiddenByOverlay = mode == Mode.FULLSCREEN && fullOverlay.visibility != View.VISIBLE
        epgPane.visibility = if (hiddenByOverlay) View.INVISIBLE else View.VISIBLE
    }

    private fun paint(slots: Slots) {
        val alphas = floatArrayOf(PREV_ALPHA, 1f, NEXT_ALPHA)
        val items = listOf(slots.prev, slots.now, slots.next)
        items.forEachIndexed { index, item -> bind(epgRows[index], item, alphas[index]) }
        epgProgress.visibility = if (slots.now == null) View.INVISIBLE else View.VISIBLE
        epgProgressFill.scaleX = slots.elapsedFraction(System.currentTimeMillis())
    }

    private fun bind(row: Row, item: Programme?, alpha: Float) {
        row.root.alpha = alpha
        row.root.visibility = if (item == null) View.INVISIBLE else View.VISIBLE
        row.time.text = item?.let { clock.format(Date(it.start)) } ?: ""
        row.title.text = item?.title ?: ""
    }

    // endregion

    private fun toast(title: String, message: String) {
        toastView.text = "$title · $message"
        toastView.visibility = View.VISIBLE
        ui.removeCallbacks(hideToastRunnable)
        ui.postDelayed(hideToastRunnable, TOAST_MS)
    }

    private fun prefs() = getSharedPreferences("efir", Context.MODE_PRIVATE)

    private fun dp(value: Float) = (value * resources.displayMetrics.density).toInt()

    private inner class RoundedOutline : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, dp(14f).toFloat())
        }
    }

    // endregion

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)

        if (mode == Mode.FULLSCREEN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK -> exitFullscreen()
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER -> toggleOverlay()
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> stepAll(-1)
                KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_MEDIA_NEXT -> stepAll(1)
                else -> return super.dispatchKeyEvent(event)
            }
            return true
        }

        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (channelList.hasFocus()) {
                    enterFullscreen()
                    return true
                }
                if (rail.hasFocus()) {
                    channelList.requestFocus()
                    channelAdapter.requestFocusAt(channelIndex)
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> if (channelList.hasFocus()) {
                rail.requestFocus()
                railAdapter.requestFocusAt(categoryIndex)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun toggleOverlay() {
        if (fullOverlay.visibility == View.VISIBLE) setOverlay(false) else setOverlay(true)
    }

    companion object {
        private const val LAST_KEY = "last_channel"
        private const val PREVIEW_SHARE = 0.42f
        private const val ZAP_DEBOUNCE_MS = 420L
        private const val OVERLAY_TIMEOUT_MS = 4000L
        private const val TOAST_MS = 3500L
        private const val EPG_TICK_MS = 15_000L
        private const val GUIDE_RESERVE_DP = 116f
        private const val GUIDE_WIDTH_DP = 420f
        private const val PREV_ALPHA = 0.35f
        private const val NEXT_ALPHA = 0.55f
    }
}

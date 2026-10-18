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
import plus.efir.tv.data.Kind
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
    private lateinit var fullHint: TextView
    private lateinit var toastView: TextView
    private lateinit var hintBar: TextView
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
    private var films: List<Channel> = emptyList()

    /** Channels plus films: everything a favourite star or a "last watched" key can point at. */
    private var pooled: List<Channel> = emptyList()
    private var epgIds: Set<String> = emptySet()
    private var favorites: MutableList<String> = mutableListOf()
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
        hintBar = findViewById(R.id.hint_bar)
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

        favorites = loadFavorites()
        buildCategories()

        rail.layoutManager = LinearLayoutManager(this)
        channelList.layoutManager = LinearLayoutManager(this)
        railAdapter = RailAdapter(onPicked = { showCategory(it) })
        channelAdapter = ChannelAdapter(
            onFocused = { channel -> onRowFocused(channel) },
            onOpened = { enterFullscreen() },
            onFlag = { channel -> toggleFavorite(channel, fromFlag = true) },
            isFavorite = { channel -> favorites.contains(channel.key) },
            resumeOf = { channel -> resumeOf(channel) },
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
        saveResume()
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
        films = repo.films()
        pooled = allChannels + films
        epgIds = allChannels.mapNotNull { it.epgId }.toSet()
        if (pooled.isEmpty()) {
            categories = emptyList()
            return
        }
        val favourites = Category(getString(R.string.favorites), favouriteChannels())
        // Film shelves sit right after the catch-alls so the library is the second thing the remote reaches.
        val movieShelves = repo.filmByGroup().map { (title, items) -> Category(title, items) }
        val liveGroups = repo.byGroup().map { (title, channels) -> Category(title, channels) }
        categories = listOf(Category(getString(R.string.all_channels), allChannels), favourites) +
            movieShelves + liveGroups
    }

    /** Follows the order they were added, not the playlist order. */
    private fun favouriteChannels(): List<Channel> =
        favorites.mapNotNull { key -> pooled.firstOrNull { it.key == key } }

    private fun loadFavorites(): MutableList<String> =
        prefs().getString(FAVORITES_KEY, "")?.split(',')
            ?.map { it.trim() }?.filter { it.isNotEmpty() }?.toMutableList() ?: mutableListOf()

    /** Prefer the real group over the "Все" and "Избранные" catch-alls so the list opens where the channel lives. */
    private fun categoryOf(channel: Channel): Int {
        val match = categories.withIndex().firstOrNull { (position, category) ->
            position > FAVORITES_INDEX && category.channels.any { it.key == channel.key }
        }
        return match?.index ?: 0
    }

    private fun restoreLastChannel() {
        val lastKey = prefs().getString(LAST_KEY, null)
        val last = pooled.firstOrNull { it.key == lastKey } ?: pooled.firstOrNull() ?: return
        categoryIndex = categoryOf(last)
        channelIndex = categories[categoryIndex].channels.indexOfFirst { it.key == last.key }.coerceAtLeast(0)
    }

    private fun onRowFocused(channel: Channel) {
        val position = categories.getOrNull(categoryIndex)?.channels
            ?.indexOfFirst { it.key == channel.key } ?: return
        if (position < 0) return
        channelIndex = position
        // A film has nothing to preview: scrolling the shelf must not start a two-hour download.
        if (channel.kind == Kind.Film) {
            ui.removeCallbacks(zapRunnable)
            return
        }
        if (tuner.channel?.key == channel.key) return
        ui.removeCallbacks(zapRunnable)
        ui.postDelayed(zapRunnable, ZAP_DEBOUNCE_MS)
    }

    private fun tuneNow() {
        ui.removeCallbacks(zapRunnable)
        val channel = current() ?: return
        // A film only starts when he asks for it with ОК, never while the shelf is scrolling.
        if (channel.kind == Kind.Film) {
            persistCurrent()
            return
        }
        tuner.tune(channel, startAt = resumeOf(channel) ?: 0)
        persistCurrent()
    }

    private fun current(): Channel? =
        categories.getOrNull(categoryIndex)?.channels?.getOrNull(channelIndex)

    private fun showCategory(index: Int, keepKey: String? = current()?.key) {
        if (categories.isEmpty()) {
            paneTitle.text = getString(R.string.empty_playlist)
            return
        }
        categoryIndex = index.coerceIn(0, categories.lastIndex)
        val category = categories[categoryIndex]
        paneTitle.text = "${category.title} · ${category.channels.size}"
        channelIndex = category.channels.indexOfFirst { it.key == keepKey }.coerceAtLeast(0)
        hintBar.text = when {
            categoryIndex == FAVORITES_INDEX && category.channels.isEmpty() ->
                getString(R.string.hint_favorites)
            category.isFilmShelf -> getString(R.string.hint_films)
            else -> getString(R.string.hint_browse)
        }
        channelAdapter.submit(category.channels)
    }

    private fun toggleFavorite(channel: Channel, fromFlag: Boolean = false) {
        val added = !favorites.remove(channel.key)
        if (added) favorites.add(channel.key)
        prefs().edit().putString(FAVORITES_KEY, favorites.joinToString(",")).apply()
        toast(
            channel.name,
            getString(if (added) R.string.added_favorite else R.string.removed_favorite)
        )
        buildCategories()
        showCategory(categoryIndex, channel.key)
        channelList.requestFocus()
        // Un-favouriting inside the "Избранные" tab removes the row, so only the flag that is
        // still on screen can keep focus.
        if (fromFlag && current()?.key == channel.key) {
            channelAdapter.requestFlagFocusAt(channelIndex)
        } else {
            channelAdapter.requestFocusAt(channelIndex)
        }
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
        val target = current() ?: return
        if (mode == Mode.FULLSCREEN || pooled.isEmpty()) return
        ui.removeCallbacks(zapRunnable)
        if (target.kind == Kind.Film) {
            // A film is started by ОК only, and only when nothing of it is already running.
            if (tuner.channel?.key != target.key || tuner.isIdle) tuner.tune(target, resumeOf(target) ?: 0)
        } else if (tuner.channel?.key != target.key) {
            tuneNow()
        }
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
        fullHint.setText(
            if (target.kind == Kind.Film) R.string.hint_film_fullscreen else R.string.hint_fullscreen
        )
        renderStatus()
        setOverlay(true)
    }

    private fun exitFullscreen() {
        if (mode != Mode.FULLSCREEN) return
        saveResume()
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
        fullTitle.text = if (channel.kind == Kind.Film) channel.name
        else "${channel.number}. ${channel.name}"
        fullStatus.text = tuner.statusLine
        // Reaching the end drops the saved position, so the film starts clean next time.
        if (tuner.isFinished) saveResume()
        refreshEpgPane()
    }

    // region own library positions

    /** Where this film should continue from, or null when it was never really started. */
    private fun resumeOf(channel: Channel): Long? =
        if (channel.kind == Kind.Film)
            prefs().getLong("resume:${channel.key}", 0L).takeIf { it > Tuner.MIN_RESUME_MS }
        else null

    private fun saveResume() {
        val channel = tuner.channel ?: return
        if (channel.kind != Kind.Film) return
        val at = tuner.positionMs
        val length = tuner.durationMs
        val finished = length > 0 && length - at < FINISH_MARGIN_MS
        prefs().edit().putLong("resume:${channel.key}", if (finished) 0L else at).apply()
    }

    // endregion

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
            // One table for both: on a live channel the arrows zap, on a film the same keys seek.
            val step = when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> -SEEK_SMALL_MS
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> SEEK_SMALL_MS
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> -SEEK_BIG_MS
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_MEDIA_NEXT -> SEEK_BIG_MS
                else -> 0L
            }
            val select = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                event.keyCode == KeyEvent.KEYCODE_ENTER ||
                event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
            when {
                event.keyCode == KeyEvent.KEYCODE_BACK -> exitFullscreen()
                select -> toggleOverlay()
                step != 0L -> if (tuner.channel?.kind == Kind.Film) {
                    // Holding the key repeats it, so the jump grows instead of needing a third speed.
                    seek(step * (event.repeatCount + 1))
                } else stepAll(if (step > 0) 1 else -1)
                else -> return super.dispatchKeyEvent(event)
            }
            return true
        }

        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> when {
                channelAdapter.isFlagFocused() -> return true
                channelList.hasFocus() -> {
                    channelAdapter.requestFlagFocusAt(channelIndex)
                    return true
                }
                rail.hasFocus() -> {
                    channelList.requestFocus()
                    channelAdapter.requestFocusAt(channelIndex)
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> when {
                channelAdapter.isFlagFocused() -> {
                    channelAdapter.requestFocusAt(channelAdapter.flagPosition)
                    return true
                }
                channelList.hasFocus() -> {
                    rail.requestFocus()
                    railAdapter.requestFocusAt(categoryIndex)
                    return true
                }
            }
            // From a flag, arrows step back onto the rows so zapping keeps following the selection.
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> if (channelAdapter.isFlagFocused()) {
                val step = if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1
                val target = (channelAdapter.flagPosition + step).coerceIn(0, channelAdapter.itemCount - 1)
                channelAdapter.requestFocusAt(target)
                return true
            }
            KeyEvent.KEYCODE_MENU -> if (channelList.hasFocus() && !channelAdapter.isFlagFocused()) {
                current()?.let { toggleFavorite(it) }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun toggleOverlay() {
        if (fullOverlay.visibility == View.VISIBLE) setOverlay(false) else setOverlay(true)
    }

    /** The overlay carries the timecode — the remote has no seek bar to draw. */
    private fun seek(deltaMs: Long) {
        val to = tuner.seekBy(deltaMs)
        if (to == null) toast(tuner.channel?.name ?: "", "перемотка включится, когда фильм начнётся")
        else setOverlay(true)
    }

    companion object {
        private const val LAST_KEY = "last_channel"
        private const val FAVORITES_KEY = "favorites"
        private const val FAVORITES_INDEX = 1
        private const val PREVIEW_SHARE = 0.42f
        private const val ZAP_DEBOUNCE_MS = 420L
        private const val OVERLAY_TIMEOUT_MS = 4000L
        private const val TOAST_MS = 3500L
        private const val EPG_TICK_MS = 15_000L
        private const val SEEK_SMALL_MS = 30_000L
        private const val SEEK_BIG_MS = 300_000L
        private const val GUIDE_RESERVE_DP = 116f
        private const val GUIDE_WIDTH_DP = 420f
        private const val PREV_ALPHA = 0.35f
        private const val NEXT_ALPHA = 0.55f

        /** Closer than this to the end counts as watched: next time it starts from the beginning. */
        private const val FINISH_MARGIN_MS = 90_000L
    }
}

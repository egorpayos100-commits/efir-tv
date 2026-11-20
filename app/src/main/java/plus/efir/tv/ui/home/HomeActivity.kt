package plus.efir.tv.ui.home

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewParent
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.updateLayoutParams
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import plus.efir.tv.BuildConfig
import plus.efir.tv.CrashReport
import plus.efir.tv.EfirApp
import plus.efir.tv.R
import plus.efir.tv.data.Channel
import plus.efir.tv.data.ChannelRepository
import plus.efir.tv.data.EpgGuide
import plus.efir.tv.data.Kind
import plus.efir.tv.data.ListHealer
import plus.efir.tv.data.Programme
import plus.efir.tv.data.Resolvers
import plus.efir.tv.data.SelfUpdate
import plus.efir.tv.data.Slots
import plus.efir.tv.data.Source
import plus.efir.tv.data.SourceHealth
import plus.efir.tv.data.SourceStamps
import plus.efir.tv.data.Trailers
import plus.efir.tv.data.VkVideo
import plus.efir.tv.player.LogoLoader
import plus.efir.tv.player.PlaybackLog
import plus.efir.tv.player.Tuner
import plus.efir.tv.player.formatTimecode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Random
import java.util.concurrent.Executors

class HomeActivity : Activity() {

    private enum class Mode { PREVIEW, FULLSCREEN }

    /** The five words across the top: every shelf of the screen is one press along this line. */
    private enum class Tab { LIVE, FILMS, GUIDE, FAVORITES, SETTINGS }

    /** One line of the guide: start time + programme name. */
    private class Row(val root: View, val time: TextView, val title: TextView)

    private lateinit var root: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var browsePane: LinearLayout
    private lateinit var tabs: RecyclerView
    /** The one diagnosis line of the guide and the cinema, on the tab bar where it costs no row. */
    private lateinit var tabStatus: TextView
    private lateinit var heroFrame: View
    private lateinit var heroArt: ImageView
    private lateinit var heroText: View

    /** The window inside the hero band that holds the player while he browses. */
    private lateinit var previewBox: FrameLayout
    private lateinit var previewArt: ImageView

    /** The channel whose mark stands in the window while nothing is tuned. */
    private var previewArtKey: String? = null
    private lateinit var heroName: TextView
    private lateinit var heroProgramme: View
    private lateinit var heroProgress: View
    private lateinit var heroProgressFill: View
    private lateinit var heroRows: List<Row>
    /** Which channel the band was last drawn for: it is not repainted while the key is the same. */
    private var heroKey: String? = null
    private lateinit var channelList: RecyclerView
    /** Softens the top edge of the wall so it reads as sliding under the pinned header. */
    private lateinit var listFade: View
    private lateinit var channelOverlay: RecyclerView
    private lateinit var fullOverlay: View
    private lateinit var fullTitle: TextView
    private lateinit var fullStatus: TextView
    private lateinit var toastView: TextView
    private lateinit var epgPane: LinearLayout
    private lateinit var epgRows: List<Row>
    private lateinit var epgProgress: View
    private lateinit var epgProgressFill: View

    /** A VK page is watched in this WebView: its player is the only thing that can open the link. */
    private lateinit var vkWeb: WebView
    private var vkKey: String? = null
    private var vkName: String? = null

    /** The same WebView shows a YouTube trailer; only the page it is allowed to walk into differs. */
    private var webTrailer = false

    /** Where a trailer goes when its own page cannot load: the video's watch page always works. */
    private var webFallback: String? = null
    private var webReturn: Channel? = null
    private var webBackToDetail = false

    /** The film's own bar over a web page: state, time gone and the line to the end. */
    private lateinit var webBar: View
    private lateinit var webBarState: ImageView
    private lateinit var webBarLabel: TextView
    private lateinit var webBarTime: TextView
    private lateinit var webBarFill: View
    private lateinit var webBarThumb: View

    /** The answer to a press in the middle of the picture: a paused film or a seek of so many seconds. */
    private lateinit var webHint: View
    private lateinit var webHintIcon: ImageView
    private lateinit var webHintText: TextView

    private val hideWebHint = Runnable {
        webHint.animate().alpha(0f).setDuration(WEB_HINT_FADE).withEndAction {
            webHint.visibility = View.GONE
        }.start()
    }

    /** A paused picture keeps its bar on screen: that is where he reads that he stopped it. */
    private var webBarPaused = false
    private val hideWebBar = Runnable {
        if (vkKey != null && !webBarPaused) webBar.visibility = View.GONE
    }

    /** The page is the only one who knows the position, so the bar asks it once a second. */
    private val webBarTick = object : Runnable {
        override fun run() {
            if (vkKey == null) return
            webBarRead()
            ui.postDelayed(this, WEB_BAR_MS)
        }
    }

    /** Where the film was left last time, applied once the page really starts playing. */
    private var vkResumeAt = 0L
    private var vkResumeApplied = false

    /** Presses at the embed's own play button, because the remote's ОК never reaches its DOM. */
    private var vkTries = 0

    /** VK may stand between the box and the film with its «not a robot» plate; ОК presses it. */
    private var vkCaptchaTries = 0
    private var vkCaptchaNoted = false

    private val vkPlayTick = object : Runnable {
        override fun run() {
            if (vkKey == null) return
            vkTries++
            vkTryPlay()
            if (vkTries < VK_TRIES) {
                ui.postDelayed(this, VK_TRY_MS)
            } else if (fullStatus.text.toString().startsWith(webLabel())) {
                fullStatus.text = getString(R.string.web_press, webLabel())
                setOverlay(true)
            }
        }
    }

    /**
     * The trailer's own state, read out of the wrapper page: an iframe is a different origin, so the
     * picture inside it is invisible from here — the page reports what the player is doing. A player
     * that never started gets a press on the plate in the middle, and after a few of those the app
     * stops and says so instead of leaving him with black glass.
     */
    private var trailerTaps = 0
    private val trailerTick = object : Runnable {
        override fun run() {
            if (vkKey == null || !webTrailer) return
            vkWeb.evaluateJavascript(TRAILER_STATE_JS) { raw ->
                if (vkKey == null || !webTrailer) return@evaluateJavascript
                val parts = raw?.trim()?.removeSurrounding("\"").orEmpty().split('|')
                val state = parts.getOrNull(0).orEmpty()
                val point = parts.getOrNull(2)?.split(',')
                val x = point?.getOrNull(0)?.toFloatOrNull()
                val y = point?.getOrNull(1)?.toFloatOrNull()
                when {
                    state == "playing" -> {
                        fullStatus.text = getString(R.string.web_playing, webLabel())
                        return@evaluateJavascript
                    }
                    state == "ended" -> {
                        fullStatus.text = getString(R.string.web_press, webLabel())
                        setOverlay(true)
                        return@evaluateJavascript
                    }
                    state == "error" -> {
                        // Embedding refused or the video is gone: the watch page still plays it.
                        val fallback = webFallback
                        webFallback = null
                        if (fallback != null) {
                            vkWeb.loadUrl(fallback)
                        } else {
                            fullStatus.text = getString(R.string.web_trailer_error)
                            setOverlay(true)
                        }
                        return@evaluateJavascript
                    }
                }
                // A plate waiting for a press, and a player that stopped on its own: those are the
                // two states worth a press. «buffering» is already working and must not be paused.
                val waiting = state == "cued" || state == "paused"
                if (waiting && x != null && y != null && trailerTaps < TRAILER_TAPS) {
                    trailerTaps++
                    vkTap(x, y)
                }
                ui.postDelayed(this, TRAILER_TICK_MS)
            }
        }
    }

    /**
     * ОК is a press on the picture, not a command to the element: VK's own player keeps the state, so
     * a script that called play() behind its back was re-paused by it a frame later. The tap goes
     * first and this reads back what the page actually did.
     */
    private var vkVerifyWant = false
    private val vkVerify = Runnable {
        if (vkKey == null) return@Runnable
        vkWeb.evaluateJavascript(WEB_STATE_JS) { raw ->
            if (vkKey == null) return@evaluateJavascript
            val playing = raw?.trim()?.removeSurrounding("\"") == "playing"
            if (playing == vkVerifyWant) {
                webStateLine(playing)
                return@evaluateJavascript
            }
            val force = if (vkVerifyWant) {
                "v.muted=false;var p=v.play();if(p&&p.catch)p.catch(function(){});"
            } else "v.pause();"
            vkWeb.evaluateJavascript(
                "(function(){" + WEB_VIDEO + "if(!v)return 'none';" + force + "return 'ok';})()"
            ) { _ ->
                if (vkKey == null) return@evaluateJavascript
                vkClean()
                // play() is a promise: reading the element now would still report the old state.
                ui.postDelayed({
                    if (vkKey == null) return@postDelayed
                    vkWeb.evaluateJavascript(WEB_STATE_JS) { again ->
                        if (vkKey == null) return@evaluateJavascript
                        webStateLine(again?.trim()?.removeSurrounding("\"") == "playing")
                    }
                }, VK_VERIFY_MS)
            }
        }
    }

    /** A film on a web page has no player of ours to ask, so its position is read off the page itself. */
    private val vkPositionTick = object : Runnable {
        override fun run() {
            if (vkKey == null) return
            vkReadPosition()
            ui.postDelayed(this, VK_POSITION_MS)
        }
    }

    // region cinema: the new release over the shelf, and one film opened from its poster

    /** The four newest films the cinema banner rotates through, and the one on screen now. */
    private var billFilms: List<Channel> = emptyList()
    private var billIndex = 0

    private lateinit var detailPane: View
    private lateinit var detailBackdrop: ImageView
    private lateinit var detailPoster: ImageView
    private lateinit var detailBadge: TextView
    private lateinit var detailTitle: TextView
    private lateinit var detailMeta: TextView
    private lateinit var detailDescription: TextView
    private lateinit var detailResume: TextView
    private lateinit var detailWatch: TextView
    private lateinit var detailTrailer: TextView
    private lateinit var detailFavorite: TextView
    private lateinit var detailBack: TextView
    private var detailFilm: Channel? = null

    /** Shelf title and row to come back to: un-starring a film can delete the shelf it lived on. */
    private var detailReturn: Pair<String?, String?>? = null
    private var detailFromHero = false

    /** A film on a direct address rather than a web page: «Назад» still owes him the card. */
    private var playerReturn: Channel? = null

    // endregion

    private lateinit var tuner: Tuner
    private lateinit var epg: EpgGuide
    private lateinit var health: SourceHealth
    private lateinit var update: SelfUpdate
    private lateinit var tabAdapter: TabAdapter
    private lateinit var channelAdapter: ChannelAdapter
    private lateinit var pickerAdapter: ChannelAdapter
    private lateinit var tileAdapter: TileAdapter
    private lateinit var shelfAdapter: ShelfAdapter

    private var categories: List<Category> = emptyList()
    private var allChannels: List<Channel> = emptyList()

    /** Channels plus films: everything a favourite star or a "last watched" key can point at. */
    private var pooled: List<Channel> = emptyList()
    private var epgIds: Set<String> = emptySet()
    private var favorites: MutableList<String> = mutableListOf()

    /** How many times a row was really watched: the order «Избранное» is sorted by. */
    private val watches = HashMap<String, Int>()

    /** The row a finished watch is credited to; cleared the moment he zaps away. */
    private var pendingWatch: String? = null

    private val watchTick = Runnable {
        val key = pendingWatch ?: return@Runnable
        pendingWatch = null
        val playing = tuner.channel ?: return@Runnable
        if (playing.key != key || !tuner.isPlaying) return@Runnable
        watches[key] = (watches[key] ?: 0) + 1
        prefs().edit().putString(
            WATCH_KEY,
            watches.entries.joinToString(",") { "${it.key}:${it.value}" }
        ).apply()
        // Counting a watch must not repaint the shelf he is reading: the lists are re-sorted quietly
        // and the cursor follows its own row, so the new order shows up on the next shelf he opens.
        val keep = current()?.key
        buildCategories()
        categories.getOrNull(categoryIndex)?.let { shelf ->
            if (keep != null) channelIndex = shelf.channels.indexOfFirst { it.key == keep }.coerceAtLeast(0)
        }
    }

    /** Which of the five tabs is open, and the order they are drawn in along the top. */
    private var tab = Tab.LIVE
    private val tabOrder = listOf(Tab.LIVE, Tab.FILMS, Tab.GUIDE, Tab.FAVORITES, Tab.SETTINGS)

    private var categoryIndex = 0
    private var channelIndex = 0
    private var mode = Mode.PREVIEW

    /** The row the preview window is showing: the next ОК on it goes full screen, not back to the tuner. */
    private var previewKey: String? = null

    /** The size, tab and category the current layout parameters were computed for. */
    private var geomKey: String? = null

    /** A geometry pass already waits for the running layout to finish. */
    private var geomPending = false

    /** ОК asked for the whole screen and the surface is still being handed over. */
    private var fullRequested = false

    /** Set while he is looking at search results instead of the shelves. */
    private var query: String? = null


    /** He pressed the updater row, so the build that check finds must start downloading itself. */
    private var autoInstall = false

    /** Shelf and row to come back to when MENU closes the playlist pane again. */
    private var playlistReturn: Pair<String?, String?>? = null

    /** Channel typed with the digits on the remote, waiting for the number to stop growing. */
    private var digits = ""

    /** Row the picker last held focus on: what the debounced zap must tune. */
    private var pickerTarget: Channel? = null

    /** While a field or a list is open on screen, digits belong to typing, not to channel numbers. */
    private var openDialog: AlertDialog? = null

    /** A channel that ran out of addresses: the next playlist load gets one more try on it. */
    private var awaitingFresh: Channel? = null

    /**
     * The box is often still bringing its network up when he opens the app, and the first pull then
     * fails quietly — the cinema stayed on the three films inside the build. A failed pull is retried
     * a few times while the screen is open, so «нет фильмов» stops depending on timing.
     */
    private var pullRetries = 0
    private val pullRetry = Runnable {
        if (!foreground) return@Runnable
        (applicationContext as EfirApp).repository.refresh()
    }

    /** Rescued channels: a second lookup for the same row would only restart the failure loop. */
    private val healed = HashSet<String>()

    /** When a channel's addresses were last knocked on by the zap guard. */
    private val guarded = HashMap<String, Long>()

    /** Row subtitle per channel: what is on its air right now, recomputed on the minute tick. */
    private val onAir = HashMap<String, String>()

    /** The rescue lookup downloads playlists, so it never runs on the UI thread. */
    private val healer = Executors.newSingleThreadExecutor { task ->
        Thread(task, "healer").apply { isDaemon = true }
    }

    private val ui = Handler(Looper.getMainLooper())
    private val clock = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val stamp = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
    private val hideRunnable = Runnable { setOverlay(false) }
    private val hideToastRunnable = Runnable { toastView.visibility = View.GONE }
    private val digitRunnable = Runnable { playDigits() }
    private val checkRunnable = object : Runnable {
        override fun run() {
            if (!health.isRunning) return
            healthProgressLine()
            ui.postDelayed(this, CHECK_TICK_MS)
        }
    }

    /** Signed addresses die on a clock, so the lists are pulled again while the screen stays open. */
    private val pullRunnable = Runnable {
        (applicationContext as EfirApp).repository.refresh()
        schedulePull()
    }

    private val epgTicker = object : Runnable {
        override fun run() {
            refreshEpgPane()
            refreshProgrammes()
            // A minute moves every bar in the guide, not only the row he happens to have open.
            if (guideOpen()) tileAdapter.repaint()
            // The cards carry the same tracks: they catch up between programme changes too.
            shelfAdapter.refreshBars()
            epg.refresh()
            // While the air is really starving it owns the network: the dump waits instead of being
            // thrown away, and a longer hold than the tick means consecutive starved ticks chain
            // together. It used to hold on FULLSCREEN as well — with a 20 s hold on a 15 s tick that
            // chain never ended, so while he watched TV the guide downloaded nothing, ever. The read
            // is throttled now instead of banned, and only real starvation gets the pipe.
            if (tuner.buffering) epg.holdPipe(EPG_HOLD_MS)
            reportGuideStatus()
            ui.postDelayed(this, EPG_TICK_MS)
        }
    }

    /** «Всё вылетело и потухло» leaves no Java stack when the system kills us, so the proof that we
     *  were alive one minute before the dark goes to disk on its own schedule. */
    private val beatRunnable = object : Runnable {
        override fun run() {
            PlaybackLog.beat("экран горит · ${tuner.channel?.name ?: "ничего не играет"}")
            ui.postDelayed(this, BEAT_MS)
        }
    }

    /**
     * The advert does not only sit where the zap landed: cinerama moves it onto an edge that was
     * clean minutes ago, and mid-channel nothing errors, so the stall watchdog never fires.
     * Re-knocking on the addresses of the channel on air is the only way to see it.
     */
    private val slateTick = object : Runnable {
        override fun run() {
            if (mode != Mode.FULLSCREEN) return
            if (foreground) guardAgainstSlate(force = true)
            ui.postDelayed(this, SLATE_MS)
        }
    }

    /** The list over the picture is a copy of the whole table: it only needs rebuilding when opened. */
    private var pickerStale = true

    /** Whether the screen is in the foreground: the player must not start behind a hidden window. */
    private var foreground = false

    /** The lists became ready while the screen was hidden: tune as soon as it is back. */
    private var pendingTune = false

    /** The strip fades over this many pixels of travel; read once, not for every scrolled pixel. */
    private val fadeSpan by lazy { dp(24f).toFloat() }

    /** Whether the window currently holds the screen awake, so the flag is touched only on change. */
    private var screenHeld = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        root = findViewById(R.id.root)
        playerView = findViewById(R.id.player_view)
        browsePane = findViewById(R.id.browse_pane)
        tabs = findViewById(R.id.tabs)
        tabStatus = findViewById(R.id.tab_status)
        heroFrame = findViewById(R.id.hero_frame)
        heroArt = findViewById(R.id.hero_art)
        heroText = findViewById(R.id.hero_text)
        previewBox = findViewById(R.id.preview_box)
        previewArt = findViewById(R.id.preview_art)
        heroName = findViewById(R.id.hero_name)
        heroProgramme = findViewById(R.id.hero_programme)
        heroProgress = findViewById(R.id.hero_progress)
        heroProgressFill = findViewById(R.id.hero_progress_fill)
        heroProgressFill.pivotX = 0f
        heroRows = listOf(
            Row(findViewById(R.id.hero_prev), findViewById(R.id.hero_prev_time), findViewById(R.id.hero_prev_title)),
            Row(findViewById(R.id.hero_now), findViewById(R.id.hero_now_time), findViewById(R.id.hero_now_title)),
            Row(findViewById(R.id.hero_next), findViewById(R.id.hero_next_time), findViewById(R.id.hero_next_title)),
        )
        channelList = findViewById(R.id.channel_list)
        listFade = findViewById(R.id.list_fade)
        channelOverlay = findViewById(R.id.channel_overlay)
        fullOverlay = findViewById(R.id.full_overlay)
        fullTitle = findViewById(R.id.full_title)
        fullStatus = findViewById(R.id.full_status)
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

        webBar = findViewById(R.id.web_bar)
        webBarState = findViewById(R.id.web_bar_state)
        webBarLabel = findViewById(R.id.web_bar_label)
        webBarTime = findViewById(R.id.web_bar_time)
        webBarFill = findViewById(R.id.web_bar_fill)
        webBarFill.pivotX = 0f
        webBarThumb = findViewById(R.id.web_bar_thumb)
        webHint = findViewById(R.id.web_hint)
        webHintIcon = findViewById(R.id.web_hint_icon)
        webHintText = findViewById(R.id.web_hint_text)

        detailPane = findViewById(R.id.film_detail)
        detailBackdrop = findViewById(R.id.detail_backdrop)
        detailPoster = findViewById(R.id.detail_poster)
        detailBadge = findViewById(R.id.detail_badge)
        detailTitle = findViewById(R.id.detail_title)
        detailMeta = findViewById(R.id.detail_meta)
        detailDescription = findViewById(R.id.detail_description)
        detailResume = findViewById(R.id.detail_resume)
        detailWatch = findViewById(R.id.detail_watch)
        detailTrailer = findViewById(R.id.detail_trailer)
        detailFavorite = findViewById(R.id.detail_favorite)
        detailBack = findViewById(R.id.detail_back)
        detailWatch.setOnClickListener { detailFilm?.let { playFilm(it) } }
        detailTrailer.setOnClickListener { detailFilm?.let { playTrailer(it) } }
        detailFavorite.setOnClickListener { detailFilm?.let { toggleFavorite(it) } }
        detailBack.setOnClickListener { closeDetail() }

        playerView.outlineProvider = RoundedOutline()
        playerView.clipToOutline = true

        // The WebView is not built here: Chromium is tens of megabytes and its renderer threads keep
        // a 1 GB box busy, and he browses channels far more often than he opens a film. It is made
        // on the first VK film or trailer instead.
        tuner = Tuner(this)
        playerView.player = tuner.player
        tuner.onState = { renderStatus() }
        tuner.onEvent = { title, message -> toast(title, message) }
        tuner.onExhausted = { dead -> onSourcesExhausted(dead) }
        tuner.onGaveUp = { dead -> heal(dead) }

        val app = applicationContext as EfirApp
        epg = app.epg
        epg.onLoaded = { refreshEpgPane() }
        health = app.health
        health.onUpdated = { onHealthUpdated() }
        update = app.update
        update.onUpdated = { onUpdateChanged() }
        app.repository.onRemote = { onRemoteLoaded() }

        favorites = loadFavorites()
        watches.putAll(loadWatches())

        tabs.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        channelList.layoutManager = LinearLayoutManager(this)
        channelOverlay.layoutManager = LinearLayoutManager(this)
        // Rows arrive and leave with focus moves; a list animator only costs frames on this box.
        listOf(tabs, channelList, channelOverlay).forEach {
            it.itemAnimator = null
            // Every row is a fixed height and the list fills its pane: without this a table change
            // measures and lays the whole wall out twice before it draws.
            it.setHasFixedSize(true)
        }
        // The arrows are the only way down from the tabs and back up to them: the box's own focus
        // search would otherwise stay inside whichever list happens to be first in the throw order.
        tabs.nextFocusDownId = R.id.channel_list
        channelList.nextFocusUpId = R.id.tabs
        // A card that scrolls out keeps its view: rebinding a row of posters mid-glide is what made
        // them blink away at the edge and come back blank.
        channelList.setItemViewCacheSize(10)
        tabAdapter = TabAdapter(
            labels = tabOrder.map { getString(tabLabel(it)) },
            onPicked = { showTab(it) },
        )
        // The cinema list is the only one that carries the banner of the newest release, so it is
        // built here rather than by the shared helper that also serves the list over the picture.
        channelAdapter = ChannelAdapter(
            onFocused = { onRowFocused(it) },
            onOpened = { openRow(it) },
            onFlag = { channel -> toggleFavorite(channel, fromFlag = true) },
            isFavorite = { channel -> favorites.contains(channel.key) },
            resumeOf = { channel -> resumeOf(channel) },
            noteOf = { channel -> noteOf(channel) },
            isDead = { channel -> health.stateOf(channel) == SourceHealth.State.Dead },
            programmeOf = { channel -> onAir[channel.key] },
            onWatch = { film -> playFilm(film) },
            onTrailer = { film -> playTrailer(film) },
            onMore = { film -> openDetail(film, fromHero = true) },
            metaOf = { film -> filmLine(film, short = true) },
            hasTrailer = { film -> Trailers.embedUrl(film.trailerUrl) != null },
        )
        shelfAdapter = ShelfAdapter(
            onFocused = { shelf, card, channel -> onShelfFocused(shelf, card, channel) },
            onOpened = { openRow(it) },
            onFlag = { channel -> toggleFavorite(channel, fromFlag = true) },
            resumeOf = { channel -> resumeOf(channel) },
            playingKey = { tuner.channel?.key },
            isDead = { channel -> health.stateOf(channel) == SourceHealth.State.Dead },
            nowOf = { channel -> onAir[channel.key]?.substringBefore(" · ") },
            runOf = { channel -> runOfChannel(channel) },
        )
        pickerAdapter = newAdapter(
            onFocused = { onPickerFocused(it) },
            onOpened = { openFromPicker(it) },
        )
        tileAdapter = TileAdapter(
            onOpened = { openTile(it) },
            tileOf = { tileOf(it) },
            isDead = { channel -> health.stateOf(channel) == SourceHealth.State.Dead },
            playingKey = { tuner.channel?.key },
        )
        tabs.adapter = tabAdapter
        channelList.adapter = channelAdapter
        // The fade over the wall's top edge is there only while something is scrolled under the
        // header; at rest it would just dim the first row for nothing.
        channelList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) = refreshListFade()
        })
        channelOverlay.adapter = pickerAdapter

        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyGeometry() }
        setOverlay(false)
        CrashActivity.startIfCrashed(this)

        // Playlists, guide lines and verdicts are read on the app's init thread: opening the tables
        // here instead would freeze the first seconds of every start.
        tabStatus.text = getString(R.string.pane_loading)
        if (app.ready) bootstrap() else app.onReady = { bootstrap() }
    }

    /** Everything that needs the channel tables in memory, once they have been read. */
    private fun bootstrap() {
        restoreLastChannel()
        showCategory(categoryIndex)
        // The cinema opens on its banner, the shelves on the row he left.
        focusTab()
        // He may have pressed HOME while the lists were still being read: no sound from a hidden screen.
        if (foreground) tuneWelcome() else pendingTune = true
        // Signed addresses die on a clock, so lists pulled once at open are not enough.
        (applicationContext as EfirApp).repository.refresh()
        schedulePull()
        // The settings tab carries the updater row, so its verdict is already there when he looks.
        update.check()
        // Never probed at open: a pass that times out on a busy box used to bury channels it never
        // saw. He starts it from the playlist pane when he wants it.
    }

    /**
     * The band opens on a channel of its own: the same one all day, a different one tomorrow. It
     * used to open on whatever he watched last, so the band said «Cinema» every single evening.
     */
    private fun tuneWelcome() {
        if (mode != Mode.PREVIEW) return
        if (tab != Tab.LIVE) return
        if (tuner.channel != null && !tuner.isIdle) return
        val live = allChannels.filter { !it.isAction && it.sources.isNotEmpty() && !isDead(it) }
        if (live.isEmpty()) {
            tuneNow()
            return
        }
        val day = System.currentTimeMillis() / 86_400_000L
        val channel = live[Random(day * 31 + live.size).nextInt(live.size)]
        // The cursor goes to the same channel the window tunes: a band that shows one channel while
        // the list highlights another reads as a mistake.
        val shelf = categories.indexOfFirst { it.channels.any { row -> row.key == channel.key } }
        if (shelf >= 0) {
            categoryIndex = shelf
            channelIndex = categories[shelf].channels.indexOfFirst { it.key == channel.key }
            if (tab == Tab.LIVE) shelfAdapter.focus(channel.key)
        }
        tuner.tune(withBackups(channel))
        guardAgainstSlate()
        previewKey = channel.key
    }

    /** Re-arms the in-screen playlist pull on the interval chosen in the playlist pane. */
    private fun schedulePull() {
        ui.removeCallbacks(pullRunnable)
        val hours = (applicationContext as EfirApp).repository.refreshHours()
        ui.postDelayed(pullRunnable, hours * 3_600_000L)
    }

    /** Mid-pass the check row is the only thing that moves: redrawing the table drops the cursor. */
    private fun healthProgressLine() {
        channelAdapter.updateRow(SETTINGS_CHECK)
    }

    /**
     * The box dims and sleeps after a few minutes without keys, which is wrong while something is
     * on air: the window asks for the screen as long as a stream is loaded, and lets go on stop.
     */
    private fun applyKeepScreen() {
        val playing = vkKey != null || (tuner.channel != null && !tuner.isIdle)
        if (playing == screenHeld) return
        screenHeld = playing
        if (playing) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** The same row widget is used twice: the browse pane and the list over the picture. */
    private fun newAdapter(
        onFocused: (Channel) -> Unit,
        onOpened: (Channel) -> Unit,
    ) = ChannelAdapter(
        onFocused = onFocused,
        onOpened = onOpened,
        onFlag = { channel -> toggleFavorite(channel, fromFlag = true) },
        isFavorite = { channel -> favorites.contains(channel.key) },
        resumeOf = { channel -> resumeOf(channel) },
        noteOf = { channel -> noteOf(channel) },
        isDead = { channel -> health.stateOf(channel) == SourceHealth.State.Dead },
        programmeOf = { channel -> onAir[channel.key] },
    )

    override fun onStart() {
        super.onStart()
        foreground = true
        applyKeepScreen()
        if (tuner.channel != null || pendingTune) {
            // Cold start: the band opens on its own channel. Back from the system: the air and the
            // whole screen pick up what they were holding.
            val cold = pendingTune && tuner.channel == null
            pendingTune = false
            when {
                mode == Mode.FULLSCREEN -> tuneNow()
                cold -> tuneWelcome()
                else -> resumeScreen()
            }
        }
        ui.post(epgTicker)
        ui.post(beatRunnable)
        // The banner walk is cancelled with the screen, so coming back re-arms it.
        if (tab == Tab.FILMS) scheduleBillboard()
        PlaybackLog.recordSession("экран открыт")
        ui.postDelayed(checkRunnable, CHECK_TICK_MS)
        // The remote lists are the reason a link stays valid: pull them every time the screen opens.
        if ((applicationContext as EfirApp).ready) {
            (applicationContext as EfirApp).repository.refresh()
            schedulePull()
        }
        // Hours left on a signed address is a clock reading, so it is repainted every time he looks.
        channelAdapter.refreshStatuses()
    }

    override fun onStop() {
        super.onStop()
        foreground = false
        // A hidden WebView keeps both its audio and its decoder: the page is torn down with the screen.
        if (vkKey != null) closeVk()
        ui.removeCallbacks(epgTicker)
        ui.removeCallbacks(beatRunnable)
        ui.removeCallbacks(checkRunnable)
        ui.removeCallbacks(pullRunnable)
        ui.removeCallbacks(pullRetry)
        ui.removeCallbacks(watchTick)
        ui.removeCallbacks(billTick)
        ui.removeCallbacks(beatRunnable)
        saveResume()
        tuner.stop()
        previewKey = null
        applyKeepScreen()
        PlaybackLog.recordSession("экран закрыт")
    }

    override fun onDestroy() {
        ui.removeCallbacks(hideRunnable)
        ui.removeCallbacks(hideToastRunnable)
        ui.removeCallbacks(digitRunnable)
        ui.removeCallbacks(epgTicker)
        ui.removeCallbacks(checkRunnable)
        ui.removeCallbacks(pullRunnable)
        ui.removeCallbacks(pullRetry)
        ui.removeCallbacks(watchTick)
        ui.removeCallbacks(billTick)
        ui.removeCallbacks(beatRunnable)
        ui.removeCallbacks(vkPlayTick)
        ui.removeCallbacks(vkVerify)
        ui.removeCallbacks(vkPositionTick)
        ui.removeCallbacks(webBarTick)
        ui.removeCallbacks(trailerTick)
        ui.removeCallbacks(hideWebBar)
        if (::vkWeb.isInitialized) {
            vkWeb.stopLoading()
            (vkWeb.parent as? ViewGroup)?.removeView(vkWeb)
            vkWeb.destroy()
        }
        (applicationContext as EfirApp).apply {
            onReady = null
            repository.onRemote = null
        }
        epg.onLoaded = null
        health.onUpdated = null
        update.onUpdated = null
        tuner.onState = null
        tuner.onEvent = null
        tuner.onExhausted = null
        tuner.onGaveUp = null
        healer.shutdownNow()
        tuner.release()
        super.onDestroy()
    }

    // region selection

    /**
     * The row says what is on its air instead of which shelf it sits on: the group is readable from
     * the rail, and «what is playing now» is the thing he actually scrolls the list for. Only the rows
     * whose words changed get repainted — a whole-table redraw would throw the cursor away.
     */
    private fun refreshProgrammes() {
        val now = System.currentTimeMillis()
        val changed = ArrayList<String>()
        allChannels.forEach { channel ->
            val slots = epg.slots(channel.epgId, now)
            val item = slots.now
            val line = item?.let { "${it.title} · ${clock.format(Date(it.start))} · ${channel.group}" }
            if (line == null) {
                if (onAir.remove(channel.key) != null) changed.add(channel.key)
            } else if (onAir[channel.key] != line) {
                onAir[channel.key] = line
                changed.add(channel.key)
            }
        }
        changed.forEach { key ->
            channelAdapter.updateRow(key)
            pickerAdapter.updateRow(key)
            tileAdapter.updateRow(key)
        }
        // The shelves say what is on air too, and a rebind keeps the cursor where it is.
        if (changed.isNotEmpty()) shelfAdapter.refreshNow()
    }

    /** A card's track: how much of the programme on air has run, or -1 while the guide is silent. */
    private fun runOfChannel(channel: Channel): Float {
        val now = System.currentTimeMillis()
        val slots = epg.slots(channel.epgId, now)
        return if (slots.now == null) -1f else slots.elapsedFraction(now)
    }

    /** A tile: the programme on air now, its hours, how far it has run. A gap shows what starts next. */
    private fun tileOf(channel: Channel): Tile {
        val now = System.currentTimeMillis()
        val slots = epg.slots(channel.epgId, now)
        val item = slots.now ?: slots.next
        if (item == null) return Tile(null, "", "", 0f)
        val run = if (slots.now == null) 0f else slots.elapsedFraction(now)
        return Tile(item.title, clock.format(Date(item.start)), clock.format(Date(item.end)), run)
    }

    /**
     * ОК on a tile: the channel it names becomes the selection and fills the screen. Focus alone
     * never starts anything here — walking 30 tiles must not put 30 streams on the decoder.
     */
    private fun openTile(channel: Channel) {
        val shelf = categories.getOrNull(categoryIndex) ?: return
        channelIndex = shelf.channels.indexOfFirst { it.key == channel.key }
        if (channelIndex < 0) return
        enterFullscreen()
    }

    /**
     * The tile table hides the SurfaceView to take its whole width, and hiding destroys the surface.
     * Preparing a stream in that same frame lets the decoder start in the middle of a long GOP, which
     * on these edges is seconds of sound with nothing to look at — so the view goes back first and the
     * tune waits for a live surface. Bounded: after a few passes it tunes anyway.
     */
    private fun withPlayerSurface(tries: Int = 0, action: () -> Unit) {
        playerView.visibility = View.VISIBLE
        val holder = (playerView.videoSurfaceView as? SurfaceView)?.holder
        if (holder == null || tries >= SURFACE_TRIES || holder.surface.isValid) {
            action()
        } else {
            playerView.post { withPlayerSurface(tries + 1, action) }
        }
    }

    private fun buildCategories() {
        val repo = (applicationContext as EfirApp).repository
        val live = ordered(repo.channels())
        allChannels = live
        pooled = allChannels + repo.films()
        pickerStale = true
        epgIds = allChannels.mapNotNull { it.epgId }.toSet()
        epg.useIds(epgIds)
        refreshProgrammes()
        categories = when (tab) {
            Tab.LIVE -> liveShelves()
            Tab.FAVORITES -> favouriteShelves()
            Tab.FILMS -> {
                val films = repo.films()
                if (films.isEmpty()) emptyList()
                else listOf(Category(getString(R.string.tab_films), films, Category.FILM_SHELF))
            }
            Tab.GUIDE -> listOf(
                Category(getString(R.string.guide_rail), guideRows(), Category.GUIDE_SHELF)
            )
            Tab.SETTINGS -> listOf(
                Category(getString(R.string.tab_settings), settingsRows(), Category.SETTINGS_SHELF)
            )
        }
        tabStatus.text = statusLine()
    }

    /** Home: his starred channels first, then his own groups, each group a shelf of cards. */
    private fun liveShelves(): List<Category> {
        val shelves = ArrayList<Category>()
        val found = searchResults()
        if (found != null) {
            shelves.add(Category(getString(R.string.search_found, found.size), found, SEARCH_SHELF))
        }
        val starred = ordered(favouriteChannels()).filter { it.kind != Kind.Film }
        if (starred.isNotEmpty()) {
            shelves.add(Category(getString(R.string.favorites), starred, Category.FAVORITES_SHELF))
        }
        allChannels.groupBy { it.group }
            .toList().sortedBy { it.first }
            .forEach { (title, channels) ->
                // Channels keep their exact authored playlist order: no shuffling under the user's feet
                shelves.add(Category(title, channels))
            }
        return shelves
    }

    /** The favourites tab keeps the two kinds apart: channels one shelf, films another. */
    private fun favouriteShelves(): List<Category> {
        val shelves = ArrayList<Category>()
        val starred = ordered(favouriteChannels())
        val channels = starred.filter { it.kind != Kind.Film }
        if (channels.isNotEmpty()) {
            shelves.add(Category(getString(R.string.favorites), channels, Category.FAVORITES_SHELF))
        }
        val films = starred.filter { it.kind == Kind.Film }
        if (films.isNotEmpty()) {
            shelves.add(Category(getString(R.string.favorites_films), films, Category.FAVORITES_FILMS_SHELF))
        }
        return shelves
    }

    /** The one muted line on the tab bar: where the guide stands, or who supplied the cinema. */
    private fun statusLine(): String = when (tab) {
        Tab.GUIDE -> getString(
            R.string.pane_guide_count, getString(R.string.guide_rail),
            epg.coverage(epgIds), allChannels.size, BuildConfig.VERSION_NAME
        )
        Tab.FILMS -> "${(applicationContext as EfirApp).repository.films().size} · ${
            getString(filmsSourceLabel())
        } · v${BuildConfig.VERSION_NAME}"
        else -> ""
    }

    /** The films the banner walks through: the marked releases, newest first, at most four. */
    /**
     * The banners of the cinema. The film the harvester marked newest takes the first banner; the
     * rest are shuffled by the date, so the row of banners changes once a day and not on every
     * glance at the wall.
     */
    private fun billboardFilms(category: Category): List<Channel> {
        val films = category.channels.filter { it.kind == Kind.Film && it.sources.isNotEmpty() }
        if (films.isEmpty()) return emptyList()
        val lead = films.firstOrNull { it.featured } ?: films.first()
        val day = System.currentTimeMillis() / 86_400_000L
        val rnd = Random(day * 53 + films.size)
        // One key per film, drawn before the sort: a selector that calls nextInt() again on every
        // comparison contradicts itself, and TimSort answers that with an exception.
        val rest = films.filter { it.key != lead.key }
            .map { it to rnd.nextInt() }
            .sortedBy { it.second }
            .map { it.first }
        return (listOf(lead) + rest).take(BILLBOARD_MAX)
    }

    private val billTick = object : Runnable {
        override fun run() {
            rotateBillboard()
            ui.postDelayed(this, BILL_MS)
        }
    }

    private fun scheduleBillboard() {
        ui.removeCallbacks(billTick)
        ui.postDelayed(billTick, BILL_MS)
    }

    /**
     * Four banners take turns. The cursor keeps its place: a banner that swaps under it puts the
     * same button back under it, so a held ОК never lands on the wrong film.
     */
    private fun rotateBillboard() {
        if (billFilms.size < 2) return
        if (tab != Tab.FILMS || mode != Mode.PREVIEW) return
        val held = channelAdapter.billHasFocus()
        billIndex = (billIndex + 1) % billFilms.size
        channelAdapter.setBillboard(billFilms[billIndex], billIndex, billFilms.size)
        if (held) channelList.post {
            // Only a cursor the swap itself dropped is put back. One that has already walked down
            // to a poster, or up to the tabs, belongs to him — pulling it back to the banner is
            // what made a press on the banner look like it went nowhere.
            if (currentFocus == null || channelAdapter.billHasFocus()) channelAdapter.requestBillFocus()
        }
    }

    /** Search is a shelf of its own, so the rest of the rails stay exactly where the cursor is. */
    private fun searchResults(): List<Channel>? {
        val text = query?.trim()?.lowercase()?.ifEmpty { null } ?: return null
        return pooled.filter { it.name.lowercase().contains(text) || it.group.lowercase().contains(text) }
    }

    /** A channel is grey only when every one of its addresses answered and refused us. */
    private fun isDead(channel: Channel) =
        channel.sources.isNotEmpty() && health.stateOf(channel) == SourceHealth.State.Dead

    private fun ordered(list: List<Channel>): List<Channel> =
        list.sortedBy { if (it.kind == Kind.Live && isDead(it)) 1 else 0 }

    /** Rows of the playlist pane: addresses to edit, checks to run, switches to flip. */
    private fun settingsRows(): List<Channel> {
        val repo = (applicationContext as EfirApp).repository
        fun row(key: String, label: String) = Channel(
            key = key,
            name = label,
            group = getString(R.string.settings_rail),
            logoUrl = null,
            sources = emptyList(),
            kind = Kind.Film,
            isAction = true,
        )
        val stamped = stampedChannels().size
        val expired = expiredChannels().size
        val urls = repo.channelUrls()
        val loaded = repo.channelsUpdatedAt()
        val sources = if (repo.usingDefaultPlaylists())
            getString(R.string.settings_sources_default, originLabel(urls.joinToString(" + "), repo.channelsOrigin))
        else getString(
            R.string.settings_sources, urls.size,
            originLabel(urls.firstOrNull().orEmpty(), repo.channelsOrigin)
        )
        val (alive, checked) = health.counts()
        val checkLabel = when {
            health.isRunning -> getString(R.string.check_running, health.progress())
            checked == 0 -> getString(R.string.check_idle)
            else -> getString(R.string.check_done, alive, checked - alive)
        }
        // The build answers first: it is the one row he opens this pane for, and a failed update is
        // the only thing that cannot be fixed from anywhere else in the app.
        return listOf(
            row(SETTINGS_UPDATE, getString(R.string.settings_update, update.describe())),
            row(SETTINGS_UPDATE_URL, getString(
                R.string.settings_update_url, update.url().ifBlank { "—" }.take(44)
            )),
            row(SETTINGS_SEARCH, getString(R.string.settings_search)),
            row(SETTINGS_CHANNELS, sources),
            row(SETTINGS_UPDATED, getString(
                R.string.settings_updated,
                if (loaded == 0L) getString(R.string.settings_never) else stamp.format(Date(loaded))
            )),
            row(SETTINGS_FILMS, getString(
                R.string.settings_films, originLabel(repo.filmsUrl(), repo.filmsOrigin)
            )),
            row(SETTINGS_STAMPS, if (stamped == 0) getString(R.string.settings_stamps_none)
                else getString(R.string.settings_stamps, expired, stamped - expired)),
            row(SETTINGS_CHECK, getString(R.string.settings_check, checkLabel)),
            row(SETTINGS_INTERVAL, getString(R.string.settings_interval, repo.refreshHours())),
            row(SETTINGS_EPG, getString(R.string.settings_epg, epg.url().ifBlank { "—" })),
            row(SETTINGS_LOG, getString(R.string.settings_log)),
            row(SETTINGS_CACHE, getString(R.string.settings_cache)),
            row(SETTINGS_REFRESH, getString(R.string.settings_refresh)),
        )
    }

    /** What the viewer sees as the source of a list: the address plus where it came from. */
    private fun originLabel(url: String, origin: ChannelRepository.Origin): String {
        if (url.isBlank()) return getString(R.string.origin_builtin)
        val tail = when (origin) {
            ChannelRepository.Origin.Network -> getString(R.string.origin_network)
            ChannelRepository.Origin.Cache -> getString(R.string.origin_cache)
            ChannelRepository.Origin.Asset -> getString(R.string.origin_builtin)
        }
        return "${url.take(48)} · $tail"
    }

    /**
     * Lifetime read off the saved address, in hours: signed panel links say when they die,
     * plain broadcaster addresses say nothing and get no note.
     */
    private fun noteOf(channel: Channel): String? {
        // The updater row has no address and no programme: its line is the state of the download.
        if (channel.key == UPDATE_ROW) return update.describe()
        if (channel.key == PLAYLIST_ROW) {
            val repo = (applicationContext as EfirApp).repository
            return getString(R.string.playlist_loaded, repo.channels().size, repo.films().size)
        }
        if (channel.kind != Kind.Live || channel.key.startsWith(SETTINGS_PREFIX)) return null
        if (isDead(channel)) return getString(R.string.dead_row)
        val now = System.currentTimeMillis()
        val resolver = channel.sources.firstOrNull { it.isResolver }
        if (resolver != null) return "адрес беру у канала (${resolver.resolver})"
        val expiry = channel.sources.mapNotNull { SourceStamps.expiryOf(it.url) }.maxOrNull()
            ?: return null
        val hours = ((expiry - now) / 3_600_000L).coerceAtLeast(0L)
        return if (expiry <= now) getString(R.string.note_expired, hours)
        else getString(R.string.note_alive, hours)
    }

    private fun expiredChannels(): List<Channel> {
        val now = System.currentTimeMillis()
        return allChannels.filter { channel ->
            channel.sources.isNotEmpty() && channel.sources.all { SourceStamps.isExpired(it.url, now) }
        }
    }

    /** Rows whose address still has time left on it: these are the ones that will drop tonight. */
    private fun stampedChannels(): List<Channel> =
        allChannels.filter { channel -> channel.sources.any { SourceStamps.expiryOf(it.url) != null } }

    /** A shelf that grew or finished loading must not move the cursor. */
    private fun reloadShelves() {
        val keep = current()
        buildCategories()
        val stays = keep != null &&
            categories.getOrNull(categoryIndex)?.channels?.any { it.key == keep.key } == true
        showCategory(if (stays) categoryIndex else keep?.let { categoryOf(it) } ?: categoryIndex, keep?.key)
    }

    /** The most-watched first; rows never watched keep the order they were starred in. */
    private fun favouriteChannels(): List<Channel> =
        favorites.mapNotNull { key -> pooled.firstOrNull { it.key == key } }
            .sortedByDescending { watches[it.key] ?: 0 }

    /** «Программа»: the channels he actually watches come to the top of the tile table; the rest keep
     *  the order of his own playlist (sortedByDescending is stable, so a tie changes nothing). */
    private fun guideRows(): List<Channel> =
        allChannels.sortedByDescending { watches[it.key] ?: 0 }

    /** A film's key can be a path with colons in it, so the count is read from the last colon. */
    private fun loadWatches(): Map<String, Int> =
        prefs().getString(WATCH_KEY, "")?.split(',')
            ?.mapNotNull { entry ->
                val cut = entry.lastIndexOf(':')
                val count = entry.substring(cut + 1).toIntOrNull()
                if (cut <= 0 || count == null) null else entry.substring(0, cut) to count
            }?.toMap() ?: emptyMap()

    private fun loadFavorites(): MutableList<String> =
        prefs().getString(FAVORITES_KEY, "")?.split(',')
            ?.map { it.trim() }?.filter { it.isNotEmpty() }?.toMutableList() ?: mutableListOf()

    /** Prefer a real group over «★ Избранные» and search, so the list opens where the channel lives. */
    private fun categoryOf(channel: Channel): Int {
        val match = categories.withIndex().firstOrNull { (_, category) ->
            !category.isFavorites && !category.isFavoriteFilms && !category.isSearch &&
                !category.isSettings && !category.isGuide &&
                category.channels.any { it.key == channel.key }
        }
        return match?.index ?: 0
    }

    private fun guideOpen() = tab == Tab.GUIDE

    /** The cinema wall: posters in a grid, a banner over them, and no star to walk the cursor onto. */
    private fun filmsOpen() = tab == Tab.FILMS

    private fun detailOpen() = detailPane.visibility == View.VISIBLE

    /** Up out of the shelves belongs to the tabs: they are the only thing above them. */
    private fun focusTabs() {
        tabs.requestFocus()
        tabAdapter.requestFocusAt(tabOrder.indexOf(tab))
    }

    /**
     * One word sideways. The screen changes at once and the frame follows: a bar that only moved
     * when a label managed to take the cursor left half the tabs unreachable.
     */
    private fun stepTabs(delta: Int): Boolean {
        val to = tabOrder.indexOf(tab) + delta
        if (to !in tabOrder.indices) return false
        showTab(to)
        tabAdapter.requestFocusAt(to)
        return true
    }

    /** Put the cursor back on whatever the visible list is made of. */
    private fun focusCurrent() {
        channelList.requestFocus()
        when (tab) {
            Tab.GUIDE -> tileAdapter.requestFocusAt(channelIndex)
            Tab.FILMS, Tab.SETTINGS -> channelAdapter.requestFocusAt(channelIndex)
            else -> shelfAdapter.focus(current()?.key)
        }
    }

    /** Entering a tab: the cinema starts on its banner, everything else on its own list. */
    private fun focusTab() {
        if (filmsOpen() && channelAdapter.hasBill()) {
            channelList.requestFocus()
            channelAdapter.requestBillFocus()
        } else {
            focusCurrent()
        }
    }

    /**
     * One column of tiles sideways. Left to the system this never moved on the box, so the step is
     * computed from the grid itself; at the outer columns it refuses and the caller decides.
     */
    private fun stepTiles(delta: Int): Boolean {
        val span = (channelList.layoutManager as? GridLayoutManager)?.spanCount ?: return false
        val from = tileAdapter.focusedPosition()
        if (from < 0) {
            tileAdapter.requestFocusAt(0)
            return true
        }
        val target = from + delta
        if (target !in 0 until tileAdapter.itemCount) return false
        val column = from % span
        if (delta < 0 && column == 0) return false
        if (delta > 0 && column == span - 1) return false
        tileAdapter.requestFocusAt(target)
        return true
    }

    /** MENU opens the settings tab; the same key closes it back onto the channels. */
    private fun togglePlaylist() {
        if (tab == Tab.SETTINGS) showTab(tabOrder.indexOf(Tab.LIVE), focus = true)
        else showTab(tabOrder.indexOf(Tab.SETTINGS), focus = true)
    }

    /** The five tabs, and what each one puts under them. */
    private fun showTab(index: Int, focus: Boolean = false) {
        val next = tabOrder.getOrNull(index) ?: return
        if (next == tab) {
            if (focus) focusCurrent()
            return
        }
        tab = next
        tabAdapter.select(index)
        // The tab is remembered: a restart — his own, or the box recovering the screen — must bring
        // him back to what he was reading, not to the channel tab every time. Committed at once:
        // an abrupt kill must not lose the very thing that explains where he was.
        prefs().edit().putString(TAB_KEY, next.name).commit()
        CrashReport.lastAction = "вкладка ${getString(tabLabel(next))}"
        // The trail on disk: if the screen ever lands on another tab by itself, the log says where
        // the last move came from and at what time.
        PlaybackLog.recordSession("вкладка ${getString(tabLabel(next))}")
        ui.removeCallbacks(billTick)
        buildCategories()
        showCategory(0)
        // The cinema, the guide and «Избранное» take the whole screen, with no window to play in;
        // coming back to the channel tab is when the band gets its picture again.
        if (next == Tab.LIVE) resumeAir()
        if (focus) focusTab()
    }

    private fun tabLabel(tab: Tab): Int = when (tab) {
        Tab.LIVE -> R.string.tab_live
        Tab.FILMS -> R.string.tab_films
        Tab.GUIDE -> R.string.guide_rail
        Tab.FAVORITES -> R.string.tab_favorites
        Tab.SETTINGS -> R.string.tab_settings
    }

    private fun restoreLastChannel() {
        val lastKey = prefs().getString(LAST_KEY, null)
        val last = pooled.firstOrNull { it.key == lastKey } ?: pooled.firstOrNull()
        val saved = prefs().getString(TAB_KEY, null)
        tab = saved?.let { name -> tabOrder.firstOrNull { it.name == name } }
            ?: if (last?.kind == Kind.Film) Tab.FILMS else Tab.LIVE
        tabAdapter.select(tabOrder.indexOf(tab))
        buildCategories()
        if (last == null) {
            categoryIndex = 0
            channelIndex = 0
            return
        }
        // A tab whose list holds the row he left: the cursor goes back to it. A tab that cannot
        // (the settings pane, the guide table) opens at its own start.
        val at = categories.indexOfFirst { shelf -> shelf.channels.any { it.key == last.key } }
        categoryIndex = if (at >= 0) at else 0
        channelIndex = categories.getOrNull(categoryIndex)
            ?.channels?.indexOfFirst { it.key == last.key }?.coerceAtLeast(0) ?: 0
    }

    private fun onRowFocused(channel: Channel) {
        // Playlist rows are fields: nothing to zap, nothing to remember.
        if (channel.isAction) return
        val at = categories.getOrNull(categoryIndex)?.channels?.indexOfFirst { it.key == channel.key }
        if (at != null && at >= 0) channelIndex = at
        // Scrolling is reading, not tuning: the air that was playing keeps playing, and ОК on a
        // card is the one place he says «вот это».
    }

    /** A card of a shelf took the cursor: its shelf and its place are the selection now. */
    private fun onShelfFocused(shelf: Int, card: Int, channel: Channel) {
        if (channel.isAction) return
        categoryIndex = shelf.coerceIn(0, categories.lastIndex)
        channelIndex = card
        // Walking the shelves never tunes anything: ОК is the one word that starts a stream.
    }

    private fun tuneNow(overrideChannel: Channel? = null) {
        // The stream goes first, but the guide read is only paused: killing it here is what left
        // him with no programme while he was busy testing channels.
        epg.holdPipe(EPG_HOLD_MS)
        // Zapped from the list over the picture: that row is the selection while it is open.
        if (pickerOpen()) {
            val target = pickerTarget ?: return
            vkUrlOf(target)?.let { openVk(target, it); return }
            tuner.tune(withBackups(target))
            guardAgainstSlate()
            persist(target)
            return
        }
        val channel = overrideChannel ?: current() ?: return
        // A film only starts when he asks for it with ОК, never while the shelf is scrolling.
        if (channel.kind == Kind.Film) {
            persistCurrent()
            return
        }
        val shelfIdx = categories.indexOfFirst { cat -> cat.channels.any { it.key == channel.key } }
        if (shelfIdx >= 0) {
            categoryIndex = shelfIdx
            val cardIdx = categories[shelfIdx].channels.indexOfFirst { it.key == channel.key }
            if (cardIdx >= 0) channelIndex = cardIdx
        }
        tuner.tune(withBackups(channel), startAt = resumeOf(channel) ?: 0)
        guardAgainstSlate()
        persistCurrent()
        if (mode == Mode.PREVIEW) previewKey = channel.key
    }

    /** True when the preview window already carries this very row, so ОК may give it the screen. */
    private fun previewShows(channel: Channel) =
        previewKey == channel.key && tuner.channel?.key == channel.key

    /**
     * The window inside the band is a picture, so the screens that have one open on air: the app
     * itself, and the channel tab he comes back to after the cinema stopped the stream.
     */
    private fun resumeAir() {
        val channel = current() ?: return
        if (channel.isAction || channel.kind == Kind.Film || channel.sources.isEmpty()) return
        if (previewShows(channel) && !tuner.isIdle) return
        tuner.tune(withBackups(channel))
        guardAgainstSlate()
        previewKey = channel.key
    }

    /** The screen came back and the stream was stopped with it: the air returns where he left it. */
    private fun resumeScreen() {
        if (tab == Tab.LIVE) resumeAir() else tuneWelcome()
    }

    /** First ОК: put the row on air in the preview window and stay in the list. */
    private fun startPreview(channel: Channel) {
        // A VK page has no stream of its own; its player takes the screen straight away.
        vkUrlOf(channel)?.let { openVk(channel, it); return }
        if (channel.kind != Kind.Film) {
            tuneNow(channel)
            return
        }
        if (channel.sources.isEmpty()) {
            toast(channel.name, getString(R.string.film_no_source))
            return
        }
        if (tuner.channel?.key != channel.key || tuner.isIdle) {
            tuner.tune(channel, resumeOf(channel) ?: 0)
            persist(channel)
        }
        previewKey = channel.key
    }

    /**
     * A row whose only source is a resolver gets the public lists' addresses of the same channel
     * appended as spares, so the ordinary chain has somewhere to go if the broadcaster says no.
     */
    private fun withBackups(channel: Channel): Channel {
        val ordered = skipSlates(channel)
        val resolver = ordered.sources.firstOrNull { it.isResolver } ?: return ordered
        val extra = (applicationContext as EfirApp).repository.backupSources(
            Resolvers.backupTvgIds(resolver.resolver.orEmpty()),
            exceptKey = ordered.key,
        )
        return if (extra.isEmpty()) ordered else ordered.copy(sources = ordered.sources + extra)
    }

    /**
     * A promo slate plays like a real channel — moving edge, valid segments, no error — so the
     * retry chain never rotates off it and he gets an advert for someone's app instead of his film.
     * Only the probe can tell, so its verdict moves the address to the back before the first try.
     * When every source of the row is judged dead the order stays: the old chain still runs.
     */
    private fun skipSlates(channel: Channel): Channel {
        val dead = channel.sources.filter {
            !it.isResolver && health.stateOf(it.url) == SourceHealth.State.Dead
        }
        if (dead.isEmpty() || dead.size == channel.sources.size) return channel
        return channel.copy(sources = channel.sources.filterNot { it in dead } + dead)
    }

    /**
     * The part he should never have to do again: the table pass behind the «Проверка каналов» button
     * is a manual step, and the block moves between edges inside the hours its verdicts stay valid.
     * So every zap knocks on the addresses of the row being tuned right now. Playing starts at once
     * on the first of them, and when the answers come back — a second or two later — an address that
     * turns out to be the advert is dropped for one that answered clean. Rows already judged stay put
     * for GUARD_MS, so scrolling the shelf does not re-probe it on every stop.
     */
    private fun guardAgainstSlate(force: Boolean = false) {
        val target = tuner.channel ?: return
        if (target.kind != Kind.Live) return
        val probed = target.sources.filter { !it.isResolver && it.url.isNotEmpty() }
        if (probed.isEmpty()) return
        val now = System.currentTimeMillis()
        if (!force && now - (guarded[target.key] ?: 0L) < GUARD_MS) return
        guarded[target.key] = now
        health.verifyNow(target.sources) { verdicts ->
            if (isFinishing || tuner.channel?.key != target.key) return@verifyNow
            if (target.sources.none { verdicts[it.url] == SourceHealth.State.Dead }) return@verifyNow
            tuner.abandonSlate({ source ->
                source.isResolver || verdicts[source.url] != SourceHealth.State.Dead
            }, "на экране реклама вместо канала")
        }
    }

    private fun current(): Channel? =
        categories.getOrNull(categoryIndex)?.channels?.getOrNull(channelIndex)

    private fun showCategory(index: Int, keepKey: String? = current()?.key) {
        if (categories.isEmpty()) {
            tabStatus.text = getString(R.string.empty_playlist)
            shelfAdapter.submit(emptyList())
            channelAdapter.submit(emptyList())
            tileAdapter.submit(emptyList())
            applyGeometry()
            paintHeroBand()
            return
        }
        categoryIndex = index.coerceIn(0, categories.lastIndex)
        val category = categories[categoryIndex]
        channelIndex = category.channels.indexOfFirst { it.key == keepKey }.coerceAtLeast(0)
        tabStatus.text = statusLine()
        // On the cinema the line is only the count and the build number: it cost a row of posters,
        // so it stays off the screen there.
        tabStatus.visibility =
            if (tab == Tab.FILMS || tabStatus.text.isNullOrBlank()) View.GONE else View.VISIBLE
        // «Избранное» has no window any more: a stream left on air there would be heard but never
        // seen, and leaving it running only to hide it is a waste of the box's one decoder.
        if (tab == Tab.FAVORITES && mode != Mode.FULLSCREEN) {
            tuner.stop()
            previewKey = null
        }
        when (tab) {
            Tab.GUIDE -> {
                // Nothing may keep playing behind a table he is reading.
                if (mode != Mode.FULLSCREEN) {
                    tuner.stop()
                    previewKey = null
                }
                // The guide is a 20 MB dump and a full parse: it is pulled while the decoder is
                // idle, which is exactly here.
                epg.refresh()
                tileAdapter.submit(category.channels)
            }
            Tab.FILMS -> {
                // The poster wall goes edge to edge, so a channel left on air has no window to be
                // in. A wall rebuilt under a running film is not a reason to stop that film.
                if (mode != Mode.FULLSCREEN) {
                    tuner.stop()
                    previewKey = null
                }
                billFilms = billboardFilms(category)
                billIndex = 0
                channelAdapter.submit(category.channels, billFilms.firstOrNull(), 0, billFilms.size)
                scheduleBillboard()
            }
            Tab.SETTINGS -> channelAdapter.submit(category.channels)
            else -> shelfAdapter.submit(
                categories.map { shelf ->
                    Shelf(shelf.title, shelf.channels, shelf.channels.firstOrNull()?.kind == Kind.Film)
                }
            )
        }
        paintHeroBand()
        applyGeometry()
    }

    private fun toggleFavorite(channel: Channel, fromFlag: Boolean = false) {
        if (channel.key.startsWith(SETTINGS_PREFIX)) return
        val added = !favorites.remove(channel.key)
        if (added) favorites.add(channel.key)
        prefs().edit().putString(FAVORITES_KEY, favorites.joinToString(",")).apply()
        toast(
            channel.displayName,
            getString(if (added) R.string.added_favorite else R.string.removed_favorite)
        )
        val shelfTitle = categories.getOrNull(categoryIndex)?.title
        buildCategories()
        if (detailOpen()) {
            // The page stays open on its button: only the shelf underneath it is rebuilt.
            showCategory(categories.indexOfFirst { it.title == shelfTitle }.coerceAtLeast(0), channel.key)
            paintDetailButtons(channel)
            return
        }
        showCategory(categoryIndex, channel.key)
        if (pickerOpen()) return
        channelList.requestFocus()
        // Un-favouriting inside «Избранное» removes the card, so only what is still on screen can
        // keep the cursor: the next row, or the shelf it stood on.
        if (fromFlag && current()?.key != channel.key) {
            focusCurrent()
        } else if (tab == Tab.SETTINGS) {
            channelAdapter.requestFlagFocusAt(channelIndex)
        } else {
            focusCurrent()
        }
    }

    /** Move the selection to an arbitrary channel, switching the tab and shelf if needed. */
    private fun select(channel: Channel) {
        val wanted = if (channel.kind == Kind.Film) Tab.FILMS else Tab.LIVE
        if (wanted != tab) {
            tab = wanted
            tabAdapter.select(tabOrder.indexOf(tab))
            prefs().edit().putString(TAB_KEY, wanted.name).commit()
            buildCategories()
        }
        val target = categoryOf(channel)
        if (target != categoryIndex) showCategory(target)
        channelIndex = categories.getOrNull(target)
            ?.channels?.indexOfFirst { it.key == channel.key }?.coerceAtLeast(0) ?: 0
        if (pickerOpen()) {
            pickerAdapter.requestFocusAt(allChannels.indexOfFirst { it.key == channel.key })
        } else {
            focusCurrent()
        }
        tuneNow()
    }

    /** A fetched playlist replaces the built-in list while the screen is already open. */
    private fun onRemoteLoaded() {
        val repo = (applicationContext as EfirApp).repository
        val dead = awaitingFresh
        awaitingFresh = null
        reloadShelves()
        toast("Плейлист", getString(R.string.playlist_loaded, repo.channels().size, repo.films().size))
        val fromBuild = repo.filmsOrigin == ChannelRepository.Origin.Asset
        if (fromBuild && pullRetries < PULL_RETRIES) {
            pullRetries++
            ui.removeCallbacks(pullRetry)
            ui.postDelayed(pullRetry, PULL_RETRY_MS)
        } else if (!fromBuild) {
            pullRetries = 0
        }
        // The channel that ran out of addresses gets one more try on the fresh list.
        if (dead != null) {
            val fresh = repo.channels().firstOrNull { it.key == dead.key }
                ?: repo.channels().firstOrNull { it.name.equals(dead.name, true) }
            if (fresh != null && tuner.retryWith(fresh)) {
                // A fresh copy of the row is a fresh set of addresses: the advert can be on any of them.
                guarded.remove(fresh.key)
                guardAgainstSlate()
                // A resolver row has no address in the playlist, so say what it uses instead.
                val first = fresh.sources.first()
                val shown = first.url.ifBlank { "резолвер ${first.resolver}" }
                toast(fresh.name, getString(R.string.recovered, shown.take(40)))
            } else {
                heal(dead)
            }
        }
    }

    /** Every address of a channel failed: ask the playlists for a newer copy of this row. */
    private fun onSourcesExhausted(dead: Channel) {
        awaitingFresh = dead
        (applicationContext as EfirApp).repository.refresh()
    }

    /**
     * Last resort, and the one that keeps him out of it: the same channel is looked up in the
     * published playlists and played from there. His own addresses stay his own — this only runs
     * when the row is already black, so a public list can never replace his table.
     */
    private fun heal(dead: Channel) {
        if (dead.kind != Kind.Live || dead.key in healed) {
            toast(dead.name, getString(R.string.heal_none))
            stepAll(1)
            return
        }
        healed.add(dead.key)
        val mine = allChannels
        healer.execute {
            val found = runCatching { ListHealer.sources(this, dead, mine) }.getOrDefault(emptyList())
            ui.post {
                if (found.isEmpty() || !tuner.retryWith(dead.copy(sources = found))) {
                    toast(dead.name, getString(R.string.heal_none))
                    stepAll(1)
                } else {
                    val taken = found.first().url
                    toast(dead.name, getString(R.string.heal_found, taken.take(44)))
                    PlaybackLog.recordResolve(dead.name, taken, "свои адреса мертвы, играю из общего списка")
                }
            }
        }
    }

    private fun onHealthUpdated() {
        // Mid-pass only the settings label moves: redrawing the table would drop the cursor.
        if (health.isRunning) {
            healthProgressLine()
            return
        }
        buildCategories()
        showCategory(categoryIndex, current()?.key)
    }

    /** «сайт» only when the address really answered; the copy inside the build says «вшито». */
    private fun filmsSourceLabel(): Int {
        val origin = (applicationContext as EfirApp).repository.filmsOrigin
        return if (origin == ChannelRepository.Origin.Asset) R.string.film_source_apk
        else R.string.film_source_site
    }

    /** Both pulls he can start by hand — the playlist row and the pane's «Обновить сейчас». */
    private fun refreshPlaylist() {
        val repo = (applicationContext as EfirApp).repository
        toast(
            getString(R.string.settings_rail),
            if (repo.refresh()) getString(R.string.playlist_pulling)
            else getString(R.string.playlist_busy)
        )
    }

    /**
     * The build comes over Wi-Fi, so a new version is one press here instead of a walk to the drawer
     * with the stick. The first press asks the server; the next one carries the download.
     */
    private fun pressUpdate() {
        val title = getString(R.string.update_rail)
        if (update.isAvailable) {
            when (update.install()) {
                "downloading" -> toast(title, getString(R.string.update_started))
                "busy" -> toast(title, getString(R.string.update_busy))
                "no-permission" -> {
                    toast(title, getString(R.string.update_permission))
                    ui.postDelayed({ update.openPermissionSettings() }, 1_200L)
                }
                "no-file" -> toast(
                    title, getString(R.string.update_failed, "в файле версии нет сборки .apk")
                )
                "current" -> toast(title, getString(R.string.settings_update, "уже стоит"))
            }
            return
        }
        if (update.isBusy) {
            toast(title, getString(R.string.update_busy))
            return
        }
        // One press is the whole gesture: asking and downloading are one action for him.
        autoInstall = true
        toast(title, getString(R.string.settings_update, "спрашиваю…"))
        update.check()
    }

    /** A download repaints its own row, never the shelf: the cursor must not move mid-update. */
    private fun onUpdateChanged() {
        channelAdapter.updateRow(UPDATE_ROW)
        if (tab == Tab.SETTINGS) {
            channelAdapter.updateRow(SETTINGS_UPDATE)
            channelAdapter.updateRow(SETTINGS_UPDATE_URL)
        }
        when (update.state) {
            SelfUpdate.State.Available -> if (autoInstall) {
                autoInstall = false
                pressUpdate()
            }
            SelfUpdate.State.Checking, SelfUpdate.State.Downloading -> Unit
            else -> autoInstall = false
        }
    }

    /** ОК on a playlist row opens a field or runs a command; ОК on anything else plays it. */
    private fun openRow(channel: Channel) {    if (!channel.isAction) {
            // A poster is a door, not a play button: ОК opens the film's page with its description.
            if (channel.kind == Kind.Film) {
                openDetail(channel, fromHero = false)
                return
            }
            // Two presses, as he asked: the first one puts the row on air in the preview window, the
            // second gives the whole screen to it. The window already shows this very row — straight in.
            if (mode == Mode.PREVIEW && !previewShows(channel)) {
                startPreview(channel)
                return
            }
            enterFullscreen()
            return
        }
        val repo = (applicationContext as EfirApp).repository
        when (channel.key) {
            UPDATE_ROW -> pressUpdate()
            PLAYLIST_ROW -> refreshPlaylist()
            SETTINGS_CHANNELS -> askMultiline(
                getString(R.string.url_title_sources),
                getString(R.string.url_hint_sources),
                repo.channelUrls().joinToString("\n"),
            ) { lines ->
                repo.setChannelUrls(lines)
                reloadShelves()
                repo.refresh()
            }

            SETTINGS_FILMS -> askUrl(
                getString(R.string.url_title_films), getString(R.string.url_hint), repo.filmsUrl()
            ) { value ->
                repo.setFilmsUrl(value)
                reloadShelves()
                repo.refresh()
            }

            SETTINGS_EPG -> askUrl(
                getString(R.string.url_title_epg), getString(R.string.url_hint_epg), epg.url()
            ) { value ->
                epg.setUrl(value)
                epgIds = allChannels.mapNotNull { it.epgId }.toSet()
                epg.useIds(epgIds)
                epg.refresh()
                refreshEpgPane()
            }

            SETTINGS_SEARCH -> askSearch()

            SETTINGS_UPDATE -> pressUpdate()

            SETTINGS_UPDATE_URL -> askUrl(
                getString(R.string.url_title_update), getString(R.string.url_hint_update),
                update.url()
            ) { value ->
                update.setUrl(value)
                update.check()
            }

            SETTINGS_STAMPS -> {
                val expired = expiredChannels()
                channelAdapter.refreshStatuses()
                // After a bad link a thousand names is not a message; the count carries the news.
                val shown = expired.take(12).joinToString(", ") { it.name }
                val rest = if (expired.size > 12) " · ещё ${expired.size - 12}" else ""
                toast(
                    getString(R.string.stamps_title),
                    if (expired.isEmpty()) getString(R.string.stamps_none)
                    else getString(R.string.stamps_list, shown) + rest
                )
            }

            SETTINGS_CHECK -> {
                health.check(allChannels, force = true)
                toast(getString(R.string.settings_rail), getString(R.string.check_started))
                ui.postDelayed(checkRunnable, 1_500L)
            }

            SETTINGS_INTERVAL -> askInterval()

            // The list already comes by itself on open and when a channel dies; this is the version
            // he starts by hand. The count of what arrived is toasted when the pull lands.
            SETTINGS_REFRESH -> refreshPlaylist()

            SETTINGS_LOG -> showChannelLog()

            SETTINGS_CACHE -> {
                repo.clearCache()
                health.forget()
                PlaybackLog.clear()
                reloadShelves()
                toast(getString(R.string.settings_rail), getString(R.string.cache_cleared))
            }

            else -> toast(getString(R.string.settings_rail), getString(R.string.playlist_pulling))
        }
    }

    // region cinema

    /** The band under the tabs: what is on air right now, and where in the evening it is. */
    private fun paintHeroBand() {
        val category = categories.getOrNull(categoryIndex)
        // Only the channel tab carries the band: the rest are cards edge to edge.
        val wide = tab != Tab.LIVE
        heroFrame.visibility = if (wide) View.GONE else View.VISIBLE
        if (wide) return
        val channel = heroChannel()
        heroName.text = when {
            channel == null -> getString(R.string.empty_playlist)
            // The number is the remote's business, not the band's: «14. Cinema» read as a channel
            // list cell, while the band is a poster.
            else -> channel.displayName
        }
        // The artwork only reloads when the band moved to another channel: this runs on every
        // status line of the player, which is twice a second while a stream starts.
        if (channel != null && channel.key != heroKey) {
            heroKey = channel.key
            LogoLoader.into(heroArt, channel.logoUrl, channel.swatch(HERO_W, HERO_H), HERO_PX)
        }
        paintPreview(channel)
        refreshEpgPane()
    }

    /**
     * The window shows the stream itself. While nothing is tuned there is nothing to show, so the
     * channel's mark stands on the dark plate instead of a black rectangle.
     */
    private fun paintPreview(channel: Channel?) {
        val playing = channel != null && tuner.channel?.key == channel.key && !tuner.isIdle
        previewArt.visibility = if (playing) View.GONE else View.VISIBLE
        if (!playing && channel != null && channel.key != previewArtKey) {
            previewArtKey = channel.key
            LogoLoader.into(
                previewArt, channel.logoUrl, channel.swatch(HERO_W, HERO_H), HERO_PX
            )
        }
    }

    /** The picture is what he watches; only when nothing plays does the band follow the cursor. */
    private fun heroChannel(): Channel? {
        val playing = tuner.channel
        return if (playing != null && !tuner.isIdle) playing else current()
    }

    /** Year, genre and the two ratings on one line; the short form keeps only the first genre. */
    private fun filmLine(film: Channel, short: Boolean): String {
        val parts = ArrayList<String>()
        film.releaseYear?.let { parts.add(it.toString()) }
        film.genre?.let { parts.add(if (short) it.substringBefore(',').trim() else it) }
        film.rating?.takeIf { it > 0 }?.let { parts.add("КП ${score(it)}") }
        film.imdbRating?.takeIf { it > 0 }?.let { parts.add("IMDb ${score(it)}") }
        // The shelf swaps a camera copy for an HD one and drops the mark by itself; until then the
        // line says out loud why the picture looks the way it does.
        film.quality?.let { parts.add(if (short) it else "качество $it") }
        return parts.joinToString(" · ")
    }

    private fun score(value: Double) = String.format(Locale.US, "%.1f", value)

    private fun openDetail(film: Channel, fromHero: Boolean) {
        detailReturn = categories.getOrNull(categoryIndex)?.title to current()?.key
        detailFromHero = fromHero
        showDetail(film)
    }

    /** The film's own page: still behind, official poster beside the words, and what to do with it. */
    private fun showDetail(film: Channel) {
        detailFilm = film
        // Nothing may keep talking behind the page he is reading.
        saveResume()
        tuner.stop()
        previewKey = null
        setOverlay(false)
        detailBadge.visibility = if (film.featured) View.VISIBLE else View.GONE
        detailTitle.text = film.displayName
        detailMeta.text = filmLine(film, short = false)
        val words = film.description?.trim().orEmpty()
        detailDescription.text = words
        detailDescription.visibility = if (words.isEmpty()) View.GONE else View.VISIBLE
        val at = resumeOf(film)
        detailResume.visibility = if (at == null) View.GONE else View.VISIBLE
        if (at != null) detailResume.text = getString(R.string.film_resume_at, formatTimecode(at))
        paintDetailButtons(film)
        LogoLoader.into(detailPoster, film.logoUrl, film.swatch(POSTER_W, POSTER_H), POSTER_PX)
        LogoLoader.into(
            detailBackdrop, film.backdropUrl ?: film.logoUrl, film.swatch(HERO_W, HERO_H), BACKDROP_PX
        )
        browsePane.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        browsePane.visibility = View.INVISIBLE
        epgPane.visibility = View.GONE
        detailPane.visibility = View.VISIBLE
        detailWatch.requestFocus()
        applyKeepScreen()
    }

    private fun paintDetailButtons(film: Channel) {
        detailWatch.setText(if (resumeOf(film) != null) R.string.film_continue else R.string.film_watch)
        detailTrailer.visibility =
            if (Trailers.embedUrl(film.trailerUrl) != null) View.VISIBLE else View.GONE
        detailFavorite.setText(
            if (favorites.contains(film.key)) R.string.film_favorite_remove else R.string.film_favorite_add
        )
    }

    private fun closeDetail() {
        if (!detailOpen()) return
        detailPane.visibility = View.GONE
        restoreShelf()
    }

    /** Back to the wall of posters, on the very card the page was opened from. */
    private fun restoreShelf() {
        val back = detailReturn
        val fromHero = detailFromHero
        detailReturn = null
        detailFromHero = false
        detailFilm = null
        browsePane.visibility = View.VISIBLE
        browsePane.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        buildCategories()
        val index = categories.indexOfFirst { it.title == back?.first }
        showCategory(if (index >= 0) index else categoryIndex, back?.second)
        forceGeometry()
        refreshEpgPane()
        renderStatus()
        if (fromHero && channelAdapter.hasBill()) {
            channelList.requestFocus()
            channelAdapter.requestBillFocus()
        } else {
            focusCurrent()
        }
    }

    /** «Смотреть»: the shelf row becomes the selection so the lit strip and the resume follow it. */
    private fun playFilm(film: Channel) {
        val at = categories.getOrNull(categoryIndex)?.channels?.indexOfFirst { it.key == film.key } ?: -1
        if (at >= 0) channelIndex = at
        vkUrlOf(film)?.let { openVk(film, it); return }
        if (film.sources.isEmpty()) {
            toast(film.displayName, getString(R.string.film_no_source))
            return
        }
        playerReturn = if (detailOpen()) film else null
        if (playerReturn != null) detailPane.visibility = View.INVISIBLE
        enterFullscreen()
        if (mode != Mode.FULLSCREEN && playerReturn != null) {
            playerReturn = null
            detailPane.visibility = View.VISIBLE
        }
    }

    private fun playTrailer(film: Channel) {
        val id = Trailers.idOf(film.trailerUrl)
        if (id == null) {
            toast(film.displayName, getString(R.string.film_no_trailer))
            return
        }
        openTrailer(film, id)
    }

    /**
     * One column of posters sideways, computed from the grid: the box's own focus search never moved
     * across a row, which is why → inside the cinema did nothing at all.
     */
    private fun stepFilms(delta: Int): Boolean {
        val span = (channelList.layoutManager as? GridLayoutManager)?.spanCount ?: return false
        val from = channelAdapter.steeringPosition()
        if (from < 0) {
            channelAdapter.requestFocusAt(channelIndex.coerceAtLeast(0))
            return true
        }
        val target = from + delta
        if (target !in 0 until channelAdapter.count()) return false
        val column = from % span
        if (delta % span != 0) {
            if (delta < 0 && column == 0) return false
            if (delta > 0 && column == span - 1) return false
        }
        channelAdapter.stepTo(target)
        return true
    }

    private fun detailButtons(): List<TextView> =
        listOf(detailWatch, detailTrailer, detailFavorite, detailBack).filter { it.visibility == View.VISIBLE }

    /** Arrows walk the buttons of the page; ОК presses the one that is lit. Nothing else to reach. */
    private fun pageKey(event: KeyEvent, buttons: List<TextView>, left: () -> Unit, down: () -> Unit): Boolean {
        val at = buttons.indexOfFirst { it.isFocused }
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (at > 0) buttons[at - 1].requestFocus() else if (at < 0) buttons.firstOrNull()?.requestFocus()
                else left()
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                when {
                    at < 0 -> buttons.firstOrNull()?.requestFocus()
                    at < buttons.lastIndex -> buttons[at + 1].requestFocus()
                }
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> down()
            KeyEvent.KEYCODE_DPAD_UP -> Unit
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                (buttons.getOrNull(at) ?: buttons.firstOrNull())?.performClick()
            else -> return false
        }
        return true
    }

    // endregion

    // region fields

    /** Typing with a remote is slow enough to matter, so the field opens with what is saved. */
    private fun askUrl(title: String, help: String, saved: String, onSaved: (String) -> Unit) {
        val field = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(saved)
            setSelection(saved.length)
            setSingleLine()
            hint = "http://…/playlist.m3u8?key=…"
        }
        showField(title, help, field, saved) { onSaved(it.trim()) }
    }

    /** One playlist address per line: that is how the lists are stacked into one table. */
    private fun askMultiline(title: String, help: String, saved: String, onSaved: (List<String>) -> Unit) {
        val field = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setLines(5)
            setText(saved)
            setSelection(saved.length)
            hint = "https://…/ru.m3u"
        }
        showField(title, help, field, saved) { text ->
            onSaved(text.split('\n').map { it.trim() }.filter { it.isNotEmpty() })
        }
    }

    private fun askSearch() {
        val field = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
            hint = getString(R.string.search_hint)
        }
        showField(getString(R.string.search_title), getString(R.string.search_hint), field, "") { text ->
            query = text
            val found = searchResults()?.size ?: 0
            buildCategories()
            if (found == 0) {
                toast(getString(R.string.search_title), getString(R.string.search_none))
                query = null
                buildCategories()
                showCategory(0)
                return@showField
            }
            showCategory(0)
            toast(getString(R.string.search_title), getString(R.string.search_found, found))
            channelList.requestFocus()
            channelAdapter.requestFocusAt(0)
        }
    }

    private fun showField(
        title: String,
        hint: String,
        field: EditText,
        saved: String,
        onSaved: (String) -> Unit,
    ) {
        val gutter = dp(24f)
        val holder = FrameLayout(this).apply {
            setPadding(gutter, dp(8f), gutter, 0)
            addView(field)
        }
        // The TV framework has no dialog of its own here, so the field goes in a Material alert.
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(title)
            .setMessage(hint)
            .setView(holder)
            .setPositiveButton(R.string.url_save) { _, _ -> onSaved(field.text.toString()) }
            .setNegativeButton(R.string.url_clear) { _, _ -> onSaved("") }
            .setNeutralButton(R.string.url_cancel, null)
            .create()
        dialog.setOnShowListener {
            field.requestFocus()
            field.setSelection(saved.length)
        }
        show(dialog)
    }

    /** Hours between playlist pulls: 1, 3, 6, 12 or a day. */
    private fun askInterval() {
        val repo = (applicationContext as EfirApp).repository
        val current = repo.refreshHours()
        val hours = intArrayOf(1, 3, 6, 12, 24)
        val labels = hours.map { "${it} ч" }.toTypedArray()
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(getString(R.string.settings_interval, current))
            .setSingleChoiceItems(labels, hours.indexOf(current)) { view, which ->
                repo.setRefreshHours(hours[which])
                schedulePull()
                channelAdapter.updateRow(SETTINGS_INTERVAL)
                (view as AlertDialog).dismiss()
                toast(getString(R.string.settings_rail), getString(R.string.interval_saved, hours[which]))
            }
            .setNegativeButton(R.string.url_cancel, null)
            .create()
        show(dialog)
    }

    /** The info screen: what is on air, and every failure that got logged since the last wipe. */
    private fun showChannelLog() {
        val playing = tuner.channel
        val info = if (playing == null) getString(R.string.info_title) else getString(
            R.string.info_line,
            playing.number, playing.name, playing.group,
            playing.epgId ?: "—",
            tuner.sourceIndex + 1, playing.sources.size,
            tuner.currentUrl()?.take(120) ?: "—",
            tuner.videoFormat ?: "—", tuner.error ?: "играть"
        )
        val log = PlaybackLog.read().ifBlank { getString(R.string.log_empty) }
        showText(getString(R.string.info_title), "$info\n\n${noteOfNothing()}\n\n$log".trim())
    }

    private fun noteOfNothing(): String {
        val expired = expiredChannels().size
        val stamped = stampedChannels().size
        return if (stamped == 0) getString(R.string.stamps_none)
        else getString(R.string.settings_stamps, expired, stamped - expired)
    }

    private fun showText(title: String, body: String) {
        val text = TextView(this).apply {
            setText(body.take(6_000))
            setTextColor(androidx.core.content.ContextCompat.getColor(this@HomeActivity, R.color.text_primary))
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setPadding(dp(20f), dp(12f), dp(20f), dp(12f))
        }
        val scroll = ScrollView(this).apply { addView(text) }
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton(R.string.url_cancel, null)
            .create()
        show(dialog)
    }

    // endregion

    private fun stepAll(delta: Int) {
        if (allChannels.isEmpty()) return
        val from = allChannels.indexOfFirst { it.key == current()?.key }.coerceAtLeast(0)
        val next = ((from + delta) % allChannels.size + allChannels.size) % allChannels.size
        select(allChannels[next])
    }

    private fun persistCurrent() {
        current()?.let { persist(it) }
    }

    private fun persist(channel: Channel) {
        prefs().edit().putString(LAST_KEY, channel.key).apply()
        // A row he only glanced at must not enter the count: it is credited after WATCH_MS of
        // staying on it, and the promise is dropped the moment he zaps further.
        ui.removeCallbacks(watchTick)
        pendingWatch = channel.key
        ui.postDelayed(watchTick, WATCH_MS)
    }

    // endregion

    // region geometry + chrome

    /** Re-measures even when the size, tab and category did not change: the view tree was rebuilt. */
    private fun forceGeometry() {
        geomKey = null
        applyGeometry()
    }

    private fun applyGeometry() {
        // Moving the player between its two homes while a frame is being laid out pulled a child out
        // from under FrameLayout's own loop — that NPE killed the screen and dropped him back to the
        // start. The frame after the pass is the first moment a reparent is safe.
        if (root.isInLayout || previewBox.isInLayout) {
            if (!geomPending) {
                geomPending = true
                root.post {
                    geomPending = false
                    applyGeometry()
                }
            }
            return
        }
        val width = root.width
        if (width == 0 || root.height == 0 || mode == Mode.FULLSCREEN) return
        // The band and the list re-measure while this very method is running, and a second pass
        // that sets the same parameters again keeps requesting layout — the screen then relaid
        // itself every frame. The same size, tab and category mean the same geometry.
        val key = "$width:${root.height}:$tab:$categoryIndex"
        if (key == geomKey) return
        geomKey = key
        val gutter = dp(32f)
        val category = categories.getOrNull(categoryIndex)
        // The guide, the cinema, «Избранное» and the settings take the whole width: their cards
        // are the screen, and none of them has a window to show a stream in.
        val wide = tab != Tab.LIVE
        heroFrame.visibility = if (wide) View.GONE else View.VISIBLE
        if (wide) {
            playerView.visibility = View.INVISIBLE
            epgPane.visibility = View.GONE
        } else {
            // The picture sits at the right of the band, on the same rounded plate as its words.
            val band = resources.getDimension(R.dimen.hero_height).toInt()
            val previewHeight = (band - dp(16f)).coerceAtLeast(dp(120f))
            val previewWidth = (previewHeight * 16f / 9f).toInt()
            previewBox.updateLayoutParams<FrameLayout.LayoutParams> {
                this.width = previewWidth
                this.height = previewHeight
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                leftMargin = 0
                rightMargin = dp(8f)
                topMargin = 0
                bottomMargin = 0
            }
            // The words never run under the picture.
            heroText.setPadding(dp(24f), dp(12f), previewWidth + dp(40f), dp(12f))
            placePlayer(full = false)
            previewBox.bringChildToFront(previewArt)
            playerView.visibility = View.VISIBLE
        }
        applyListLayout(category)
    }

    /**
     * The player is moved between two homes: the window inside the hero band while he browses, and
     * the whole screen while a channel has it. It cannot simply sit behind the band — the band's
     * plate is drawn over it, so a stream left there played with its sound only.
     */
    private fun placePlayer(full: Boolean) {
        val host: ViewGroup = if (full) root else previewBox
        if (playerView.parent === host) return
        (playerView.parent as? ViewGroup)?.removeView(playerView)
        playerView.updateLayoutParams<FrameLayout.LayoutParams> {
            width = FrameLayout.LayoutParams.MATCH_PARENT
            height = FrameLayout.LayoutParams.MATCH_PARENT
            gravity = Gravity.NO_GRAVITY
            leftMargin = 0
            rightMargin = 0
            topMargin = 0
            bottomMargin = 0
        }
        if (full) {
            root.addView(playerView, 0)
            playerView.clipToOutline = false
        } else {
            previewBox.addView(playerView)
            playerView.clipToOutline = true
            // The mark stays on top: an idle player is a black shutter, not a window to see through.
            previewBox.bringChildToFront(previewArt)
        }
    }

    /** Every tab draws its list its own way: shelves, a poster wall, tiles, or plain rows. */
    private fun applyListLayout(category: Category?) {
        val films = tab == Tab.FILMS
        val tiles = tab == Tab.GUIDE
        val shelves = tab == Tab.LIVE || tab == Tab.FAVORITES
        val wanted: RecyclerView.Adapter<*> = when {
            tiles -> tileAdapter
            shelves -> shelfAdapter
            else -> channelAdapter
        }
        if (channelList.adapter !== wanted) channelList.adapter = wanted
        // A lit card is drawn bigger than its cell, so the list must not cut it at the edges. It
        // still clips at its own bounds, and the padding gives the grown frame its room inside.
        val zoomed = tiles || films
        channelList.clipChildren = true
        channelList.clipToPadding = !zoomed
        val pad = if (zoomed) dp(8f) else 0
        // The shelves end with a footer of empty list: without it the last group («Спорт») sat on
        // the screen edge and its cards were cut in half with nowhere left to scroll.
        val footer = if (shelves) dp(34f) else pad
        channelList.setPadding(0, pad, 0, footer)
        var clip: ViewParent? = channelList.parent
        while (clip is ViewGroup) {
            clip.clipChildren = true
            clip = clip.parent
        }
        listFade.visibility = if (zoomed) View.VISIBLE else View.GONE
        channelList.post { refreshListFade() }
        when {
            tiles -> {
                val span = tileSpan()
                val manager = channelList.layoutManager
                if (manager !is GridLayoutManager || manager.spanCount != span) {
                    channelList.layoutManager = GridLayoutManager(this, span)
                }
            }
            films -> {
                // Six across is his ceiling, and the banner spans all six: it scrolls away with
                // the wall, which is what lets two whole rows of posters stand on the screen.
                val span = gridSpan()
                val manager = channelList.layoutManager as? GridLayoutManager
                    ?: GridLayoutManager(this, span)
                manager.spanCount = span
                manager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int) =
                        if (channelAdapter.isBill(position)) span else 1
                }
                channelAdapter.posterHeight = posterCellHeight(span)
                if (channelList.layoutManager !== manager) channelList.layoutManager = manager
            }
            else -> {
                if (channelList.layoutManager is GridLayoutManager) {
                    channelList.layoutManager = LinearLayoutManager(this)
                }
                channelAdapter.posterHeight = 0
            }
        }
    }

    /**
     * How much of the wall has gone under the header. The fade follows it, so the top edge never
     * reads as a hard cut: at rest it is invisible, and the deeper the list is scrolled, the more
     * the rows melt into the bar above them.
     */
    private fun refreshListFade() {
        val top = if (channelList.childCount > 0) channelList.getChildAt(0).top - channelList.paddingTop else 0
        val alpha = (-top / fadeSpan).coerceIn(0f, 1f)
        // The listener runs for every scrolled pixel: a repaint per pixel is what makes a weak box
        // drop frames, and a hundredth of the strip is invisible.
        if (Math.abs(listFade.alpha - alpha) < FADE_STEP) return
        listFade.alpha = alpha
    }

    /** As many tiles as the pane really fits; the guide takes the whole width, so it is the screen. */
    private fun tileSpan(): Int {
        val cell = resources.getDimension(R.dimen.tile_min_width).toInt()
        return (wideShelfWidth() / cell).coerceIn(2, MAX_TILE_COLUMNS)
    }

    /** As many poster columns as the screen really fits — never more than the six he asked for. */
    private fun gridSpan(): Int {
        val cell = resources.getDimension(R.dimen.poster_cell_min_width).toInt()
        return (wideShelfWidth() / cell).coerceIn(1, MAX_POSTER_COLUMNS)
    }

    /** A poster is 2:3: the cell is one and a half of its own widths, margins included. */
    private fun posterCellHeight(span: Int): Int {
        val width = wideShelfWidth() - channelList.paddingStart - channelList.paddingEnd
        val cell = ((width - dp(10f) * span) / span).coerceAtLeast(1)
        return (cell * 1.5f).toInt().coerceAtLeast(dp(90f))
    }

    /**
     * The width a full-width shelf has for its cards, counted rather than read off the list: the
     * list still carries the width of the shelf he was on a moment ago, and counting its columns
     * into that narrow window was why the wall came up two cards wide.
     */
    /** A full-width list has the whole pane: the tabs sit above it and take no column away. */
    private fun wideShelfWidth(): Int {
        val pane = if (browsePane.width > 0) browsePane.width else root.width
        return (pane - dp(64f)).coerceAtLeast(0)
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
        vkUrlOf(target)?.let { openVk(target, it); return }
        // The mode is switched before the surface is waited out: while the hand-over runs the screen
        // still looks like the band, and a layout pass in that window put the player back into the
        // window — the video then played where nobody could see it, with only its sound coming out.
        mode = Mode.FULLSCREEN
        fullRequested = true
        PlaybackLog.recordSession("полный экран: ${target.name}")
        // Sized before the tune is waited out: a surface created at the preview rect would flash the
        // small window back over the tiles on the way from the guide shelf.
        placePlayer(full = true)
        geomKey = null
        playerView.visibility = View.VISIBLE
        withPlayerSurface { enterFullscreenNow(target) }
    }

    private fun enterFullscreenNow(target: Channel) {
        if (!fullRequested || pooled.isEmpty()) return
        fullRequested = false
        if (target.kind == Kind.Film) {
            // A library row without a file behind it cannot be played.
            if (target.sources.isEmpty()) {
                toast(target.name, getString(R.string.film_no_source))
                mode = Mode.PREVIEW
                applyGeometry()
                return
            }
            // A film is started by ОК only, and only when nothing of it is already running.
            if (tuner.channel?.key != target.key || tuner.isIdle) {
                epg.holdPipe(EPG_HOLD_MS)
                tuner.tune(target, resumeOf(target) ?: 0)
                persist(target)
            }
        } else if (tuner.channel?.key != target.key || tuner.isIdle) {
            // The guide shelf stops the player but leaves `channel` pointing at what it stopped, so
            // the key alone is not enough to decide: the tile would open onto silence.
            tuneNow()
        }

        browsePane.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        browsePane.visibility = View.INVISIBLE
        // The guide shelf keeps the picture pane hidden to win its width; here it must come back.
        playerView.visibility = View.VISIBLE
        placeEpgOverPicture()
        renderStatus()
        setOverlay(true)
        ui.removeCallbacks(slateTick)
        ui.postDelayed(slateTick, SLATE_MS)
    }

    private fun exitFullscreen() {
        if (mode != Mode.FULLSCREEN) return
        fullRequested = false
        saveResume()
        hidePicker()
        mode = Mode.PREVIEW
        setOverlay(false)
        ui.removeCallbacks(slateTick)
        val card = playerReturn
        playerReturn = null
        if (card != null) {
            // The stop below leaves the last frame on the picture surface: over the card it would
            // read as a ghost of the film he has just left.
            playerView.visibility = View.INVISIBLE
            showDetail(card)
            return
        }
        browsePane.visibility = View.VISIBLE
        browsePane.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        applyGeometry()
        refreshEpgPane()
        if (guideOpen()) {
            // Back into the tile table means back into a shelf with no picture window at all: leaving
            // the stream running would be audio out of nowhere. ОК on a tile starts it again.
            tuner.stop()
            previewKey = null
        } else {
            // Back must land on what is on air: channelList.requestFocus() with no focused child picks
            // row zero, and that focus event is a zap.
            val onAir = tuner.channel
            if (onAir != null) {
                channelIndex = categories.getOrNull(categoryIndex)?.channels
                    ?.indexOfFirst { it.key == onAir.key }?.coerceAtLeast(0) ?: channelIndex
                previewKey = onAir.key
            }
        }
        focusCurrent()
    }

    private fun setOverlay(visible: Boolean) {
        fullOverlay.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        refreshEpgPane()
        ui.removeCallbacks(hideRunnable)
        if (visible) ui.postDelayed(hideRunnable, OVERLAY_TIMEOUT_MS)
    }

    // region channel list over the picture

    private fun pickerOpen() = channelOverlay.visibility == View.VISIBLE

    /** MENU in the player: the whole table on top of the video, so zapping does not need a way out. */
    private fun togglePicker() {
        if (pickerOpen()) {
            hidePicker()
            setOverlay(true)
            return
        }
        // The picker copies the whole table: rebuilding it here, not on every repaint, is what
        // keeps a 1300-row list off the arrow keys.
        if (pickerStale) {
            pickerAdapter.submit(allChannels)
            pickerStale = false
        }
        channelOverlay.visibility = View.VISIBLE
        setOverlay(true)
        val position = allChannels.indexOfFirst { it.key == tuner.channel?.key }.coerceAtLeast(0)
        channelOverlay.requestFocus()
        pickerAdapter.requestFocusAt(position)
    }

    /** The list over the picture reads, it does not tune: what ОК put on air stays on air. */
    private fun onPickerFocused(channel: Channel) {
        if (channel.kind == Kind.Film) return
        pickerTarget = channel
    }

    /** ОК in that list is an explicit request, so it is the one place a film may be started from. */
    private fun openFromPicker(channel: Channel) {
        vkUrlOf(channel)?.let {
            select(channel)
            hidePicker()
            openVk(channel, it)
            return
        }
        select(channel)
        hidePicker()
        if (channel.kind == Kind.Film && tuner.channel?.key != channel.key) {
            tuner.tune(channel, resumeOf(channel) ?: 0)
            persist(channel)
        }
    }

    private fun hidePicker() {
        if (!pickerOpen()) return
        channelOverlay.visibility = View.GONE
    }

    // endregion

    // region VK pages

    private fun setUpVkWeb() {
        // Built in code, not inflated with the layout: a box without a WebView provider must not
        // crash the whole screen at launch. VK rows then simply report that they cannot be watched.
        val web = runCatching { WebView(this) }.getOrNull() ?: return
        vkWeb = web
        web.setBackgroundColor(Color.BLACK)
        // The remote's keys are handled by the activity: focus must never wander into the page.
        vkWeb.isFocusable = false
        vkWeb.isFocusableInTouchMode = false
        vkWeb.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // The embed starts on its own: a D-pad cannot «tap» an HTML page.
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(vkWeb, true)
        }
        vkWeb.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                // A trailer page starts its own embed; only a VK page needs the play button tapped.
                if (vkKey != null && !webTrailer) vkTryPlay()
            }

            /**
             * The trailer wrapper is written in the app now, so the page itself cannot fail to load.
             * This fires for main-frame errors only: a failed advert frame inside the page used to
             * throw him onto YouTube's full watch page, which on this box is a black player.
             */
            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                if (!request.isForMainFrame) return
                trailerFallback(view)
            }

            @Deprecated("Still the only one that fires below API 23")
            override fun onReceivedError(
                view: WebView,
                errorCode: Int,
                description: String,
                failingUrl: String,
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) return
                trailerFallback(view)
            }

            // The player stays inside its own site: a login page or a shop is not something a D-pad
            // can use. A trailer is a YouTube embed, so that page gets the same courtesy.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val host = request.url.host?.lowercase() ?: return true
                val allowed = if (webTrailer) {
                    host == "youtube.com" || host.endsWith(".youtube.com") ||
                        host == "youtube-nocookie.com" || host.endsWith(".youtube-nocookie.com") ||
                        host == "youtu.be" || host.endsWith(".youtu.be") ||
                        host == "googlevideo.com" || host.endsWith(".googlevideo.com") ||
                        host == "ggpht.com" || host.endsWith(".ggpht.com") ||
                        host == "qoder.website" || host.endsWith(".qoder.website")
                } else {
                    host == "vk.com" || host.endsWith(".vk.com") ||
                        host == "vk.ru" || host.endsWith(".vk.ru") ||
                        host == "vkvideo.ru" || host.endsWith(".vkvideo.ru")
                }
                return !allowed
            }
        }
        // Right above the surface and below the overlays: the title strip and toasts stay visible.
        root.addView(
            web, 1,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        web.visibility = View.GONE
    }

    private fun vkUrlOf(channel: Channel): String? =
        channel.sources.asSequence().mapNotNull { VkVideo.embedUrl(it.url) }.firstOrNull()

    /**
     * A trailer that cannot be embedded at all goes to the watch page, where it always plays. The
     * attempt is spent on the first failure: a player that retries the same refused address would
     * only loop between the wrapper and YouTube.
     */
    private fun trailerFallback(view: WebView) {
        if (vkKey == null || !webTrailer) return
        val fallback = webFallback ?: return
        webFallback = null
        view.loadUrl(fallback)
    }

    /**
     * YouTube asks the viewer to agree to its cookies before it will show an embed in the EU, and
     * its «Принять все» is inside a frame a D-pad cannot reach. The consent cookie is set here, so
     * the trailer starts instead of standing on a black plate.
     */
    private fun acceptYoutubeCookies() {
        val jar = CookieManager.getInstance()
        listOf("SOCS=CAI", "CONSENT=YES+cb").forEach { cookie ->
            jar.setCookie("https://www.youtube.com", "$cookie; path=/; domain=.youtube.com")
        }
        jar.flush()
    }

    private fun webLabel() = getString(if (webTrailer) R.string.web_trailer else R.string.web_film)

    private fun openVk(channel: Channel, url: String) =
        openInWeb(channel.key, channel.displayName, url, channel, resumeOf(channel) ?: 0L, trailer = false)

    /** Closes the page without leaving the film: only the WebView's own state is dropped. */
    private fun stopVkPage() {
        vkKey = null
        vkName = null
        webReturn = null
        webBackToDetail = false
        webTrailer = false
        webFallback = null
        vkTries = 0
        vkCaptchaTries = 0
        vkCaptchaNoted = false
        vkResumeAt = 0L
        vkResumeApplied = false
        ui.removeCallbacks(vkPlayTick)
        ui.removeCallbacks(vkVerify)
        ui.removeCallbacks(vkPositionTick)
        ui.removeCallbacks(webBarTick)
        ui.removeCallbacks(trailerTick)
        hideWebBarNow()
        liftToast(overBar = false)
        vkWeb.stopLoading()
        // about:blank tears the page down: a hidden WebView keeps its audio and its decoder.
        runCatching { vkWeb.loadUrl("about:blank") }
        vkWeb.visibility = View.GONE
    }

    /** `id` is the YouTube video id: the trailer wrapper is built here, not fetched from the site. */
    private fun openTrailer(channel: Channel, id: String) = openInWeb(
        TRAILER_PREFIX + channel.key, "${channel.displayName} · трейлер", id, channel, 0L, trailer = true
    )

    private fun openInWeb(
        key: String,
        name: String,
        url: String,
        film: Channel,
        resumeAt: Long,
        trailer: Boolean,
    ) {
        if (!::vkWeb.isInitialized) setUpVkWeb()
        if (!::vkWeb.isInitialized) {
            toast(name, getString(R.string.web_no_view))
            return
        }
        val backToDetail = detailOpen()
        if (vkKey == key) {
            setOverlay(true)
            vkTryPlay()
            return
        }
        if (vkKey != null) closeVk()
        epg.holdPipe(EPG_HOLD_MS)
        saveResume()
        // One hardware decoder on this box: the ExoPlayer lets go before Chromium asks for it.
        tuner.clear()
        hidePicker()
        previewKey = null
        mode = Mode.FULLSCREEN
        browsePane.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        browsePane.visibility = View.INVISIBLE
        detailPane.visibility = View.INVISIBLE
        playerView.visibility = View.INVISIBLE
        epgPane.visibility = View.GONE
        vkKey = key
        vkName = name
        webTrailer = trailer
        webFallback = if (trailer) Trailers.watchUrl(film.trailerUrl) else null
        webReturn = film
        webBackToDetail = backToDetail
        vkResumeAt = resumeAt
        vkResumeApplied = false
        vkTries = 0
        vkCaptchaTries = 0
        vkCaptchaNoted = false
        hideWebBarNow()
        liftToast(overBar = true)
        fullTitle.text = name
        fullStatus.text = getString(R.string.web_loading, webLabel())
        fullOverlay.visibility = View.VISIBLE
        vkWeb.visibility = View.VISIBLE
        if (!trailer) persist(film)
        applyKeepScreen()
        // A trailer is a wrapper written here and given my site as its base address: the iframe then
        // carries a normal Referer, and nothing has to be fetched from a domain his provider filters.
        val opened = if (trailer) {
            acceptYoutubeCookies()
            runCatching {
                vkWeb.loadDataWithBaseURL(
                    Trailers.BASE, Trailers.html(url), "text/html", "utf-8", null
                )
            }.isSuccess
        } else {
            runCatching { vkWeb.loadUrl(url) }.isSuccess
        }
        if (!opened) {
            closeVk()
            toast(name, "WebView на этой приставке не работает")
            return
        }
        if (trailer) {
            trailerTaps = 0
            ui.removeCallbacks(trailerTick)
            ui.postDelayed(trailerTick, TRAILER_TICK_MS)
        } else {
            ui.removeCallbacks(vkPlayTick)
            ui.postDelayed(vkPlayTick, VK_TRY_MS)
            ui.removeCallbacks(vkPositionTick)
            ui.postDelayed(vkPositionTick, VK_POSITION_MS)
            ui.removeCallbacks(webBarTick)
            ui.postDelayed(webBarTick, WEB_BAR_MS)
        }
        setOverlay(true)
    }

    private fun closeVk() {
        val key = vkKey ?: return
        val film = webReturn
        val toDetail = webBackToDetail
        // One last reading before the page is torn down; the slow tick already saved the rest.
        vkReadPosition()
        vkKey = null
        vkName = null
        webReturn = null
        webBackToDetail = false
        webTrailer = false
        webFallback = null
        vkTries = 0
        vkCaptchaTries = 0
        vkCaptchaNoted = false
        vkResumeAt = 0L
        vkResumeApplied = false
        ui.removeCallbacks(vkPlayTick)
        ui.removeCallbacks(vkVerify)
        ui.removeCallbacks(vkPositionTick)
        ui.removeCallbacks(webBarTick)
        ui.removeCallbacks(trailerTick)
        hideWebBarNow()
        liftToast(overBar = false)
        vkWeb.stopLoading()
        // about:blank tears the page down: a hidden WebView keeps its audio and its decoder.
        runCatching { vkWeb.loadUrl("about:blank") }
        vkWeb.visibility = View.GONE
        mode = Mode.PREVIEW
        setOverlay(false)
        ui.removeCallbacks(slateTick)
        val rowKey = film?.key ?: key
        if (toDetail && film != null) {
            // «Назад» from the film or its trailer lands on the page it was started from.
            showDetail(film)
            channelAdapter.updateRow(rowKey)
            return
        }
        detailPane.visibility = View.GONE
        browsePane.visibility = View.VISIBLE
        browsePane.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        playerView.visibility = View.VISIBLE
        forceGeometry()
        refreshEpgPane()
        renderStatus()
        val shelf = categories.getOrNull(categoryIndex)
        val index = shelf?.channels?.indexOfFirst { it.key == rowKey } ?: -1
        if (index >= 0) channelIndex = index
        focusCurrent()
        // The card now says where the film was left, and the band offers to continue it.
        channelAdapter.updateRow(rowKey)
        paintHeroBand()
        applyKeepScreen()
    }

    /**
     * Presses the embed's own play button for him. The button lives in the page's DOM, and a click
     * made by script is not a user gesture, so the page point is tapped with a real touch event.
     */
    private fun vkTryPlay() {
        if (vkKey == null) return
        vkWeb.evaluateJavascript(WEB_TRY_JS) { raw ->
            if (vkKey == null) return@evaluateJavascript
            val result = raw?.trim()?.removeSurrounding("\"")
            when {
                result == "playing" -> {
                    vkTries = VK_TRIES
                    ui.removeCallbacks(vkPlayTick)
                    fullStatus.text = getString(R.string.web_playing, webLabel())
                    setOverlay(true)
                    vkClean()
                    vkApplyResume()
                    showWebBar()
                }
                result != null && result.startsWith("captcha:") -> {
                    // VK is checking the box is not a robot: its own «Продолжить» is pressed with a
                    // real touch, and the plate is pressed again while it stays on screen.
                    val point = result.removePrefix("captcha:").split(',')
                    val x = point.getOrNull(0)?.toFloatOrNull()
                    val y = point.getOrNull(1)?.toFloatOrNull()
                    if (x != null && y != null) vkTap(x, y)
                    vkCaptchaTries++
                    if (vkCaptchaTries <= VK_CAPTCHA_TRIES) vkTries = 1
                    if (!vkCaptchaNoted) {
                        vkCaptchaNoted = true
                        fullStatus.text = getString(R.string.web_captcha)
                        setOverlay(true)
                    }
                }
                result != null && result.startsWith("tap:") -> {
                    val point = result.removePrefix("tap:").split(',')
                    val x = point.getOrNull(0)?.toFloatOrNull()
                    val y = point.getOrNull(1)?.toFloatOrNull()
                    if (x != null && y != null) vkTap(x, y)
                }
            }
        }
    }

    /** The film starts where he left it, once — not on every repaint of the page. */
    private fun vkApplyResume() {
        if (vkResumeApplied || webTrailer) return
        vkResumeApplied = true
        val at = vkResumeAt
        if (at <= Tuner.MIN_RESUME_MS) return
        val key = vkKey ?: return
        vkWeb.evaluateJavascript(
            "(function(){" + WEB_VIDEO + "if(!v||!v.duration||!isFinite(v.duration))return 'none';" +
                "v.currentTime=Math.min(${at / 1000.0},Math.max(0,v.duration-1));return 'ok';})()"
        ) { _ ->
            if (vkKey != key) return@evaluateJavascript
            vkClean()
            toast(vkName.orEmpty(), getString(R.string.film_resume_at, formatTimecode(at)))
        }
    }

    /** A web player has no position of ours to read, so the page is asked every twenty seconds. */
    private fun vkReadPosition() {
        val key = vkKey ?: return
        if (webTrailer) return
        vkWeb.evaluateJavascript(WEB_POSITION_JS) { raw ->
            val text = raw?.trim()?.removeSurrounding("\"") ?: return@evaluateJavascript
            val parts = text.split(':')
            val at = parts.getOrNull(0)?.toLongOrNull() ?: return@evaluateJavascript
            val length = parts.getOrNull(1)?.toLongOrNull() ?: return@evaluateJavascript
            if (at <= 0L || length <= 0L) return@evaluateJavascript
            val finished = length - at < FINISH_MARGIN_MS
            prefs().edit().putLong("resume:$key", if (finished) 0L else at).apply()
        }
    }

    /**
     * The picture has no chrome of its own that a remote can read, so the bar asks the page what it
     * is doing: how far it has gone, how long it is, and whether it is running at all.
     */
    private fun webBarRead() {
        if (vkKey == null) return
        vkWeb.evaluateJavascript(WEB_PROGRESS_JS) { raw ->
            if (vkKey == null) return@evaluateJavascript
            val text = raw?.trim()?.removeSurrounding("\"") ?: return@evaluateJavascript
            // The player is not in the page yet: the bar has nothing to say and stays as it was.
            if (text == "none") return@evaluateJavascript
            val parts = text.split('|')
            val at = parts.getOrNull(0)?.toLongOrNull() ?: return@evaluateJavascript
            val length = parts.getOrNull(1)?.toLongOrNull() ?: 0L
            paintWebBar(at, length, parts.getOrNull(2) == "1")
        }
    }

    private fun paintWebBar(at: Long, length: Long, paused: Boolean) {
        webBarState.setImageResource(if (paused) R.drawable.ic_play else R.drawable.ic_pause)
        webBarLabel.setText(if (paused) R.string.web_state_paused else R.string.web_state_playing)
        webBarTime.text = getString(R.string.web_time, formatTimecode(at), formatTimecode(length))
        val fraction = if (length > 0) (at.toFloat() / length).coerceIn(0f, 1f) else 0f
        // The reading comes once a second, but the line walks to it: a bar that steps every second
        // reads as a broken clock, not as a film.
        webBarFill.animate().scaleX(fraction).setDuration(WEB_BAR_ANIM_MS).start()
        val width = webBarFill.width
        if (width > 0) {
            val dot = dp(6f)
            val thumb = (width * fraction - dot).coerceIn(0f, (width - dot * 2).toFloat())
            webBarThumb.animate().translationX(thumb).setDuration(WEB_BAR_ANIM_MS).start()
        }
        val turned = paused != webBarPaused
        webBarPaused = paused
        // A pause is the one state that must not disappear from under his eyes.
        if (turned) {
            showWebHint(if (paused) R.drawable.ic_pause else R.drawable.ic_play, null)
            showWebBar()
        }
    }

    /** Up on every press, down again while the film runs; a paused film keeps its bar. */
    private fun showWebBar() {
        if (vkKey == null) return
        webBar.visibility = View.VISIBLE
        // The first press can come before the page has answered once: an empty bar reads as broken.
        if (webBarLabel.text.isNullOrEmpty()) {
            webBarLabel.setText(if (webBarPaused) R.string.web_state_paused else R.string.web_state_playing)
            webBarTime.text = getString(R.string.web_time, formatTimecode(0L), formatTimecode(0L))
        }
        ui.removeCallbacks(hideWebBar)
        if (!webBarPaused) ui.postDelayed(hideWebBar, OVERLAY_TIMEOUT_MS)
    }

    /** The middle of the screen answers a press the way a phone player does, then clears itself. */
    private fun showWebHint(icon: Int, text: String?) {
        if (vkKey == null || webTrailer) return
        webHintIcon.setImageResource(icon)
        webHintText.text = text.orEmpty()
        webHintText.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
        webHint.visibility = View.VISIBLE
        webHint.alpha = 0f
        webHint.animate().alpha(1f).setDuration(WEB_HINT_FADE).start()
        ui.removeCallbacks(hideWebHint)
        ui.postDelayed(hideWebHint, WEB_HINT_MS)
    }

    private fun hideWebHintNow() {
        ui.removeCallbacks(hideWebHint)
        webHint.animate().cancel()
        webHint.visibility = View.GONE
        webHint.alpha = 1f
    }

    private fun hideWebBarNow() {
        ui.removeCallbacks(hideWebBar)
        webBarPaused = false
        webBar.visibility = View.GONE
        hideWebHintNow()
    }

    /** The bar owns the bottom strip while a web page is open, so the toast steps above it. */
    private fun liftToast(overBar: Boolean) {
        val params = toastView.layoutParams as? FrameLayout.LayoutParams ?: return
        params.bottomMargin = dp(if (overBar) 112f else 32f)
        toastView.layoutParams = params
    }

    private fun vkTap(x: Float, y: Float) {
        // The page reports CSS pixels; touch events travel in physical ones.
        val density = resources.displayMetrics.density
        val at = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(at, at, MotionEvent.ACTION_DOWN, x * density, y * density, 0)
        val up = MotionEvent.obtain(at, at + 60L, MotionEvent.ACTION_UP, x * density, y * density, 0)
        vkWeb.dispatchTouchEvent(down)
        vkWeb.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
    }

    /**
     * The page paints its own chrome over the film — a play plate, a strip of recommendations, a
     * watermark and, on a wide screen, a sidebar of other films. The script keeps only the path from
     * the <video> up to the page root and hides everything beside it, so the film is the screen.
     * It uses the very same finder as ОК and the arrows: one picture, one set of controls.
     */
    private fun vkClean() {
        if (vkKey == null) return
        vkWeb.evaluateJavascript(WEB_CLEAN_JS, null)
    }

    /**
     * ОК presses the picture. Driving the element behind the player's back is what left him stuck:
     * the script said «play», the page re-paused it a frame later, and the second ОК did nothing.
     * So the tap goes first, the page is read back, and only a page that ignored it gets the command.
     */
    private fun vkToggle() {
        if (vkKey == null) return
        vkWeb.evaluateJavascript(WEB_POINT_JS) { raw ->
            if (vkKey == null) return@evaluateJavascript
            val text = raw?.trim()?.removeSurrounding("\"").orEmpty()
            val parts = text.split(':')
            if (parts.size < 2) {
                vkTryPlay()
                return@evaluateJavascript
            }
            vkVerifyWant = parts[0] == "paused"
            val point = parts[1].split(',')
            val x = point.getOrNull(0)?.toFloatOrNull()
            val y = point.getOrNull(1)?.toFloatOrNull()
            if (x != null && y != null) vkTap(x, y)
            ui.removeCallbacks(vkVerify)
            ui.postDelayed(vkVerify, VK_VERIFY_MS)
        }
    }

    private fun webStateLine(playing: Boolean) {
        fullStatus.text = getString(if (playing) R.string.web_playing else R.string.web_paused, webLabel())
        setOverlay(true)
        // The press is answered at once, not when the once-a-second reading comes back.
        webBarPaused = !playing
        if (!webTrailer) {
            showWebHint(if (playing) R.drawable.ic_play else R.drawable.ic_pause, null)
            showWebBar()
            webBarRead()
        }
        // Pausing brings the page's own recommendations back; the picture stays the picture.
        vkClean()
    }

    /**
     * The arrows jump inside the film, never past its ends: an unclamped currentTime on a page that
     * had not reported its duration yet is what made the seek throw the picture away.
     */
    private fun vkSeek(deltaMs: Long) {
        if (vkKey == null) return
        val seconds = deltaMs / 1000.0
        vkWeb.evaluateJavascript(
            "(function(){" + WEB_VIDEO + "if(!v)return 'none';" +
                "var d=v.duration;if(!d||!isFinite(d))return 'live';" +
                "var t=Math.min(Math.max(0,v.currentTime+($seconds)),Math.max(0,d-1));" +
                "try{v.currentTime=t;}catch(e){return 'none';}" +
                "return Math.round(t*1000)+':'+Math.round(d*1000);})()"
        ) { raw ->
            if (vkKey == null) return@evaluateJavascript
            when (val text = raw?.trim()?.removeSurrounding("\"").orEmpty()) {
                "none" -> return@evaluateJavascript
                "live" -> toast(webLabel(), getString(R.string.web_seek_live))
                else -> {
                    val parts = text.split(':')
                    val at = parts.getOrNull(0)?.toLongOrNull()
                    val length = parts.getOrNull(1)?.toLongOrNull()
                    if (at != null && length != null) {
                        // The line and the clock move in the same beat as the arrows: the jump is
                        // visible on the bar, not only in a toast at the bottom.
                        paintWebBar(at, length, webBarPaused)
                        showWebHint(
                            if (deltaMs > 0) R.drawable.ic_forward else R.drawable.ic_rewind,
                            vkDelta(deltaMs),
                        )
                        showWebBar()
                    }
                }
            }
            vkClean()
            setOverlay(true)
        }
    }

    private fun vkDelta(deltaMs: Long): String {
        val size = Math.abs(deltaMs)
        val text = if (size >= 60_000) "${size / 60_000} мин" else "${size / 1000} с"
        return if (deltaMs > 0) "+$text" else "−$text"
    }

    // endregion

    /** Light the row that is on air in the shelf, and the same row in the list over the video. */
    private fun markPlaying(key: String?) {
        channelAdapter.playingKey = key
        pickerAdapter.playingKey = key
        shelfAdapter.markPlaying(key)
    }

    private fun renderStatus() {
        applyKeepScreen()
        // The VK page owns its picture and its controls: the overlay only carries the film's name.
        if (vkKey != null) {
            fullTitle.text = vkName.orEmpty()
            return
        }
        // The lit strip belongs to what the tuner holds, not to where the cursor is: zapping is a
        // decision, scrolling is not.
        markPlaying(tuner.channel?.key)
        val channel = tuner.channel ?: current() ?: run {
            fullTitle.text = getString(R.string.empty_playlist)
            fullStatus.text = ""
            paintHeroBand()
            return
        }
        fullTitle.text = if (channel.kind == Kind.Film) channel.displayName
        else "${channel.number}. ${channel.name}"
        fullStatus.text = tuner.statusLine
        // Reaching the end drops the saved position, so the film starts clean next time.
        if (tuner.isFinished) saveResume()
        paintHeroBand()
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
        // The film page is a screen of its own: nothing from the air belongs on it.
        if (detailOpen()) {
            epgPane.visibility = View.GONE
            heroProgramme.visibility = View.GONE
            return
        }
        val source = tuner.channel ?: current()
        val slots = epg.slots(source?.epgId, System.currentTimeMillis())
        // In fullscreen the programme is the lower-third over the picture.
        if (mode == Mode.FULLSCREEN) {
            if (slots.isEmpty) {
                epgPane.visibility = View.GONE
                return
            }
            paint(slots)
            val hiddenByOverlay = fullOverlay.visibility != View.VISIBLE
            epgPane.visibility = if (hiddenByOverlay) View.INVISIBLE else View.VISIBLE
            return
        }
        // While browsing, the programme lives inside the band; a floating panel would only cover
        // the cards, so it stays hidden there.
        epgPane.visibility = View.GONE
        if (heroFrame.visibility != View.VISIBLE) {
            heroProgramme.visibility = View.GONE
            return
        }
        paint(slots)
        heroProgramme.visibility = if (slots.isEmpty) View.GONE else View.VISIBLE
    }

    /** The last guide verdict already shown, so one failure cannot repeat itself every 15 s. */
    private var epgStatusShown: String? = null

    /**
     * The strip going blank is the one thing on this screen that has no error message, so here is
     * one: either how far the download got, or why it stopped. Nothing once the rows have data.
     */
    private fun reportGuideStatus() {
        if (mode == Mode.FULLSCREEN || toastView.visibility == View.VISIBLE) return
        val text = epg.statusText() ?: run { epgStatusShown = null; return }
        if (text == epgStatusShown) return
        epgStatusShown = text
        toastView.text = text
        toastView.visibility = View.VISIBLE
        ui.removeCallbacks(hideToastRunnable)
        ui.postDelayed(hideToastRunnable, TOAST_MS)
    }

    private fun paint(slots: Slots) {
        val alphas = floatArrayOf(PREV_ALPHA, 1f, NEXT_ALPHA)
        val items = listOf(slots.prev, slots.now, slots.next)
        items.forEachIndexed { index, item ->
            bind(epgRows[index], item, alphas[index])
            bind(heroRows[index], item, alphas[index])
        }
        val shown = if (slots.now == null) View.INVISIBLE else View.VISIBLE
        epgProgress.visibility = shown
        heroProgress.visibility = shown
        val fraction = slots.elapsedFraction(System.currentTimeMillis())
        epgProgressFill.scaleX = fraction
        heroProgressFill.scaleX = fraction
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

    private fun show(dialog: AlertDialog) {
        openDialog = dialog
        dialog.setOnDismissListener { openDialog = null }
        dialog.show()
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

        if (vkKey != null) {
            // Holding an arrow repeats it, so the jump grows instead of needing a third speed.
            val jump = event.repeatCount + 1
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK -> closeVk()
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY,
                KeyEvent.KEYCODE_MEDIA_PAUSE -> if (event.repeatCount == 0) vkToggle()
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> vkSeek(-VK_SEEK_MS * jump)
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> vkSeek(VK_SEEK_MS * jump)
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_MEDIA_NEXT -> vkSeek(VK_BIG_SEEK_MS * jump)
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> vkSeek(-VK_BIG_SEEK_MS * jump)
                // Volume, the TV button and everything else stay with the system.
                else -> return super.dispatchKeyEvent(event)
            }
            // Every press brings the bar back, like the controls of any other player.
            if (!webTrailer) showWebBar()
            return true
        }

        if (detailOpen()) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                closeDetail()
                return true
            }
            if (pageKey(event, detailButtons(), left = { }, down = { })) return true
            return super.dispatchKeyEvent(event)
        }

        val typing = openDialog?.isShowing == true
        val digit = digitOf(event.keyCode)
        if (digit != null && !pickerOpen() && !typing) {
            digits = (digits + digit).take(4)
            ui.removeCallbacks(digitRunnable)
            ui.postDelayed(digitRunnable, DIGIT_TIMEOUT_MS)
            toast(getString(R.string.digits_channel), digits)
            return true
        }

        if (typing) return super.dispatchKeyEvent(event)
        when (event.keyCode) {
            KeyEvent.KEYCODE_SEARCH -> { askSearch(); return true }
            KeyEvent.KEYCODE_MENU -> if (mode == Mode.FULLSCREEN) {
                togglePicker()
                return true
            }
        }

        if (mode == Mode.FULLSCREEN && pickerOpen()) {
            // The picker is on screen: arrows belong to the list, not to seeking or zapping.
            return when (event.keyCode) {
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU -> { hidePicker(); true }
                else -> super.dispatchKeyEvent(event)
            }
        }

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

        val guide = tab == Tab.GUIDE
        val films = tab == Tab.FILMS
        val settings = tab == Tab.SETTINGS
        val span = (channelList.layoutManager as? GridLayoutManager)?.spanCount ?: 1

        when (event.keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                if (query != null) {
                    // Search results are a shelf over «Эфир»: back closes them and leaves the list
                    // exactly where it was.
                    query = null
                    buildCategories()
                    showCategory(0)
                    focusCurrent()
                    return true
                }
                // Back climbs the screen the way he came in: first out of the list to the rail,
                // and only a press on the rail itself leaves the tab.
                if (tab != Tab.LIVE && !tabs.hasFocus()) {
                    focusTabs()
                    return true
                }
                if (tab != Tab.LIVE) {
                    showTab(tabOrder.indexOf(Tab.LIVE), focus = true)
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> when {
                // Down from the tab bar belongs to the list under it; the cinema opens on its
                // banner, everything else on the shelf he left.
                tabs.hasFocus() -> {
                    focusTab()
                    return true
                }
                // Down off the cinema banner lands on the first poster.
                channelAdapter.billHasFocus() -> {
                    channelAdapter.requestFocusAt(channelIndex.coerceAtLeast(0))
                    return true
                }
                films -> {
                    // The last row is the end of the wall: down there holds, it does not loop to the top.
                    stepFilms(span)
                    return true
                }
                guide || settings -> return super.dispatchKeyEvent(event)
                channelList.hasFocus() -> {
                    shelfAdapter.stepVertical(1)
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_UP -> when {
                tabs.hasFocus() -> return true
                films && channelAdapter.billHasFocus() -> {
                    // A held key can carry the cursor off the banner before the wall has been put
                    // back to its start, and then he stands on the tabs over bare posters with the
                    // banner scrolled out of sight. The page he leaves upward is its very top.
                    channelList.scrollToPosition(0)
                    focusTabs()
                    return true
                }
                films && channelList.hasFocus() -> {
                    // Inside the wall up is the row above, and at the top row the banner — never the
                    // tabs: the bottom of the grid used to throw him out of the cinema altogether.
                    if (!stepFilms(-span)) channelAdapter.requestBillFocus()
                    return true
                }
                guide || settings -> return super.dispatchKeyEvent(event)
                channelList.hasFocus() -> {
                    // Out of the top shelf the cursor belongs to the tabs.
                    if (!shelfAdapter.stepVertical(-1)) focusTabs()
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> when {
                tabs.hasFocus() -> {
                    stepTabs(-1)
                    return true
                }
                guide -> {
                    if (!stepTiles(-1)) focusTabs()
                    return true
                }
                films && channelAdapter.billHasFocus() -> {
                    if (!channelAdapter.billButton(-1)) focusTabs()
                    return true
                }
                films -> {
                    // The wall's left edge is the edge: the tabs are one press up, or back.
                    stepFilms(-1)
                    return true
                }
                channelAdapter.isFlagFocused() -> {
                    channelAdapter.requestFocusAt(channelAdapter.flagPosition)
                    return true
                }
                channelList.hasFocus() -> {
                    // The first card of a row is where left stops; leaving the tab is up or back.
                    shelfAdapter.stepHorizontal(-1)
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> when {
                tabs.hasFocus() -> {
                    stepTabs(1)
                    return true
                }
                guide -> {
                    stepTiles(1)
                    return true
                }
                films && channelAdapter.billHasFocus() -> {
                    channelAdapter.billButton(1)
                    return true
                }
                films -> {
                    stepFilms(1)
                    return true
                }
                channelAdapter.isFlagFocused() -> return true
                channelList.hasFocus() -> {
                    // On a settings row the star is one press to the right, so favourites stay
                    // reachable with the arrows alone; on a shelf the row itself moves sideways.
                    if (settings) channelAdapter.requestFlagFocusAt(channelIndex)
                    else shelfAdapter.stepHorizontal(1)
                    return true
                }
            }
            KeyEvent.KEYCODE_MENU -> {
                togglePlaylist()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun digitOf(keyCode: Int): String? = when (keyCode) {
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> (keyCode - KeyEvent.KEYCODE_0).toString()
        in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 ->
            (keyCode - KeyEvent.KEYCODE_NUMPAD_0).toString()
        else -> null
    }

    /** The number stopped growing: jump to that channel position in the whole table. */
    private fun playDigits() {
        val number = digits.toIntOrNull()
        digits = ""
        if (number == null) return
        val target = allChannels.firstOrNull { it.number == number }
        if (target == null) toast(getString(R.string.digits_none, number), "")
        else {
            if (mode == Mode.FULLSCREEN) hidePicker()
            select(target)
        }
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
        private const val TAB_KEY = "last_tab"
        private const val FAVORITES_KEY = "favorites"
        private const val WATCH_KEY = "watch_counts"

        /** How long he has to stay on a row before it counts as a viewing, not a glance. */
        private const val WATCH_MS = 25_000L

        /** The cinema banner walks through at most this many releases: a week of them, changed daily. */
        private const val BILLBOARD_MAX = 8

        /** How long one banner stands before the next one takes its place. */
        private const val BILL_MS = 7_000L

        /** He asked for six posters in a row at most: the wall never grows wider than that. */
        private const val MAX_POSTER_COLUMNS = 6

        /** Rows of the playlist pane are keys, not channels: they must never be starred or resumed. */
        private const val SETTINGS_PREFIX = "settings:"
        private const val SETTINGS_UPDATE = SETTINGS_PREFIX + "update"
        private const val SETTINGS_UPDATE_URL = SETTINGS_PREFIX + "update_url"

        /** The single rail row: ОК on it checks and, when there is a build, downloads it at once. */
        private const val UPDATE_ROW = SETTINGS_PREFIX + "update_now"

        /** The playlist's own row on the same shelf: pull the lists, no MENU required. */
        private const val PLAYLIST_ROW = SETTINGS_PREFIX + "playlist_now"
        private const val SETTINGS_CHANNELS = SETTINGS_PREFIX + "channels"
        private const val SETTINGS_FILMS = SETTINGS_PREFIX + "films"
        private const val SETTINGS_STAMPS = SETTINGS_PREFIX + "stamps"
        private const val SETTINGS_REFRESH = SETTINGS_PREFIX + "refresh"
        private const val SETTINGS_CHECK = SETTINGS_PREFIX + "check"
        private const val SETTINGS_INTERVAL = SETTINGS_PREFIX + "interval"
        private const val SETTINGS_EPG = SETTINGS_PREFIX + "epg"
        private const val SETTINGS_LOG = SETTINGS_PREFIX + "log"
        private const val SETTINGS_CACHE = SETTINGS_PREFIX + "cache"
        private const val SETTINGS_SEARCH = SETTINGS_PREFIX + "search"
        private const val SETTINGS_UPDATED = SETTINGS_PREFIX + "updated"
        private const val SEARCH_SHELF = Category.SEARCH_SHELF
        private const val PREVIEW_SHARE = 0.42f

        /** How long the zap guard trusts its own verdicts for one channel before knocking again. */
        private const val GUARD_MS = 90_000L

        /**
         * How often the address on air is re-checked for the advert that arrived mid-channel. Every
         * 150 с this probe downloaded segments of ALL addresses while the stream was already competing
         * for the same Wi-Fi, which is one more reason a slow edge looked dead; 29.10 raised it to
         * five minutes, so a real slate is still caught but not by stealing the pipe.
         */
        private const val SLATE_MS = 300_000L
        private const val OVERLAY_TIMEOUT_MS = 4000L
        private const val TOAST_MS = 3500L

        /** How many passes the tile table waits for the SurfaceView to come back before tuning anyway. */
        private const val SURFACE_TRIES = 6
        private const val EPG_TICK_MS = 15_000L

        /** How long the guide read sleeps for the air. Longer than a tick, so starved ticks chain. */
        private const val EPG_HOLD_MS = 20_000L
        private const val CHECK_TICK_MS = 5_000L
        private const val BEAT_MS = 5 * 60_000L
        private const val DIGIT_TIMEOUT_MS = 1_400L
        private const val SEEK_SMALL_MS = 30_000L
        private const val SEEK_BIG_MS = 300_000L

        /** How long the VK page is given to show its player before the play button is pressed. */
        private const val VK_TRY_MS = 2_000L
        private const val VK_TRIES = 8
        /** How many times the «not a robot» plate may be pressed before the box gives up. */
        private const val VK_CAPTCHA_TRIES = 20

        /** How often the trailer's state is read, and how many presses its plate gets. */
        private const val TRAILER_TICK_MS = 2_000L
        private const val TRAILER_TAPS = 4

        /** Smallest change of the top fade worth a repaint. */
        private const val FADE_STEP = 0.06f
        private const val VK_SEEK_MS = 30_000L
        private const val VK_BIG_SEEK_MS = 300_000L

        /** Long enough for the page to answer the tap, short enough that ОК still feels like a press. */
        private const val VK_VERIFY_MS = 320L

        /** How often the position of a web film is written down, so «Продолжить» survives a reboot. */
        private const val VK_POSITION_MS = 20_000L

        /** The bar's own reading: a second is fine, and the page is only asked, never driven. */
        private const val WEB_BAR_MS = 1_000L

        /** How long the line takes to walk to a new reading: a jump would read as a stutter. */
        private const val WEB_BAR_ANIM_MS = 240L

        /** The pause or the seek answer lives in the middle for a moment, then fades away. */
        private const val WEB_HINT_MS = 900L
        private const val WEB_HINT_FADE = 160L
        private const val TRAILER_PREFIX = "trailer:"

        /** A pull that came back before the box had its network gets this many more tries. */
        private const val PULL_RETRY_MS = 20_000L
        private const val PULL_RETRIES = 3

        /** Artwork sizes: a poster is a portrait window, the still behind a page is the whole screen. */
        private const val POSTER_W = 300
        private const val POSTER_H = 450
        private const val POSTER_PX = 384
        private const val HERO_W = 640
        private const val HERO_H = 360
        private const val HERO_PX = 640
        private const val BACKDROP_PX = 960

        /**
         * One finder for everything: the picture, ОК, the arrows and the cleanup all drive the same
         * element. They used to disagree — the cleanup picked the largest video with a source while
         * the controls picked the first one in the page, so ОК paused something he was not watching.
         * The player is a web component with nested shadow roots, so the page is walked, not queried.
         */
        private const val WEB_FINDER =
            "function findVideo(){var best=null,score=-1;" +
                "(function step(root){var nodes=root.querySelectorAll?root.querySelectorAll('*'):[];" +
                "for(var i=0;i<nodes.length;i++){var n=nodes[i];" +
                "if(n.tagName==='VIDEO'){var b=n.getBoundingClientRect();" +
                "var s=(n.currentSrc?1e9:0)+b.width*b.height;" +
                "if(s>score){score=s;best=n;}}" +
                "if(n.shadowRoot)step(n.shadowRoot);}})(document);return best;}"

        private const val WEB_VIDEO = WEB_FINDER + "var v=findVideo();"

        /** Position, length and the pause flag read off the very element the keys drive. */
        private val WEB_PROGRESS_JS = "(function(){" + WEB_VIDEO + "if(!v)return 'none';" +
            "var d=v.duration;if(!d||!isFinite(d))d=0;" +
            "return Math.round(v.currentTime*1000)+'|'+Math.round(d*1000)+'|'" +
            "+((v.paused||v.ended)?1:0);})()"

        private val WEB_TRY_JS = "(function(){" + WEB_VIDEO + """
              function point(el) {
                if (!el || !el.getBoundingClientRect) return null;
                var box = el.getBoundingClientRect();
                if (box.width < 2 || box.height < 2) return null;
                var st = window.getComputedStyle ? getComputedStyle(el) : null;
                if (st && (st.display === 'none' || st.visibility === 'hidden' || st.pointerEvents === 'none')) return null;
                return 'tap:' + Math.round(box.left + box.width / 2) + ',' + Math.round(box.top + box.height / 2);
              }
              if (v && !v.paused && !v.ended) {
                // Autoplay sometimes starts silent: the remote has no mute button of its own.
                v.muted = false;
                return 'playing';
              }
              // VK meets an unfamiliar box with «Проверяем, что вы не робот»: until that plate is
              // passed there is no player at all, so its own button is what has to be pressed.
              var chk = null;
              if (!v) {
                var body = document.body ? (document.body.textContent || '') : '';
                var robot = body.indexOf('робот') >= 0 || body.indexOf('not a robot') >= 0;
                (function look(root) {
                  var items = root.querySelectorAll ? root.querySelectorAll('button,[role=button],a') : [];
                  for (var i = 0; i < items.length && !chk; i++) {
                    var t = (items[i].textContent || '').trim().toLowerCase();
                    if (t === 'продолжить' || t === 'continue' || t === 'продолжить просмотр') chk = items[i];
                  }
                  var nodes = root.querySelectorAll ? root.querySelectorAll('*') : [];
                  for (var j = 0; j < nodes.length && !chk; j++) if (nodes[j].shadowRoot) look(nodes[j].shadowRoot);
                })(document);
                // Only the plate's own button counts: a «continue watching» tile is not a robot check.
                if (chk && !robot) {
                  var cls = typeof chk.className === 'string' ? chk.className.toLowerCase() : '';
                  if (cls.indexOf('start') < 0) chk = null;
                }
              }
              if (chk) {
                var plate = point(chk);
                if (plate) return 'captcha:' + plate.substring(4);
              }
              // A trailer's <video> is nought by nought until its player boots: the plate, not the
              // picture, is what can be pressed at that moment.
              var onVideo = point(v);
              if (onVideo) return onVideo;
              var byText = null;
              var byLarge = null;
              var byClass = null;
              (function step(root) {
                var items = root.querySelectorAll ? root.querySelectorAll('button,[role=button],a,div,span') : [];
                for (var i = 0; i < items.length; i++) {
                  var el = items[i];
                  if (!byText) {
                    var text = (el.textContent || '').trim().toLowerCase();
                    if (text === 'смотреть' || text === 'play' || text === 'воспроизвести') byText = el;
                  }
                  var name = typeof el.className === 'string' ? el.className.toLowerCase() : '';
                  if (!byLarge && name.indexOf('ytp-large-play-button') >= 0) byLarge = el;
                  if (!byClass && (name.indexOf('playbutton') >= 0 || name.indexOf('videoplayer_btn') >= 0 ||
                      name.indexOf('ytp-play-button') >= 0)) byClass = el;
                }
                var nodes = root.querySelectorAll ? root.querySelectorAll('*') : [];
                for (var j = 0; j < nodes.length; j++) if (nodes[j].shadowRoot) step(nodes[j].shadowRoot);
              })(document);
              // The first name worth pressing is not always the first one that can be pressed.
              var want = [byLarge, byText, byClass, document.elementFromPoint(innerWidth / 2, innerHeight / 2)];
              for (var k = 0; k < want.length; k++) {
                var at = point(want[k]);
                if (at) return at;
              }
              return 'none';
            })()
        """

        private val WEB_STATE_JS =
            "(function(){" + WEB_VIDEO + "if(!v)return 'none';" +
                "return (v.paused||v.ended)?'paused':'playing';})()"

        /** The wrapper page's own report, plus the middle of the plate a D-pad cannot reach. */
        private val TRAILER_STATE_JS =
            "(function(){return (window.__efirState||'loading')+'|'+window.__efirCode+'|'+" +
                "Math.round(innerWidth/2)+','+Math.round(innerHeight/2);})()"

        /** Where to press, and what the page thinks it is doing right now. */
        private val WEB_POINT_JS =
            "(function(){" + WEB_VIDEO + "if(!v)return 'none';" +
                "var b=v.getBoundingClientRect();if(b.width<2||b.height<2)return 'none';" +
                "return (v.paused?'paused:':'playing:')+Math.round(b.left+b.width/2)+','+" +
                "Math.round(b.top+b.height/2);})()"

        private val WEB_POSITION_JS =
            "(function(){" + WEB_VIDEO + "if(!v||!v.duration||!isFinite(v.duration))return '';" +
                "return Math.round(v.currentTime*1000)+':'+Math.round(v.duration*1000);})()"

        /**
         * Strips the page down to the picture. Everything on the path from the <video> up to the page
         * root is kept and pinned to the window; every sibling on that path — the play plate, the
         * recommendations strip, the watermark, the sidebar, the page padding — is hidden. The page
         * re-renders its overlays while the film runs, so the walk repeats on a timer.
         */
        private val WEB_CLEAN_JS = "(function(){" + WEB_FINDER + """
              function clean() {
                var video = findVideo();
                if (!video) return;
                var chain = [video];
                var node = video;
                while (node) {
                  var up = node.parentElement || (node.parentNode && node.parentNode.host) || null;
                  if (!up) break;
                  chain.push(up);
                  node = up;
                }
                for (var i = 0; i < chain.length - 1; i++) {
                  var kids = chain[i + 1].children;
                  for (var k = 0; k < kids.length; k++) {
                    var el = kids[k];
                    if (el === chain[i]) continue;
                    var tag = el.tagName;
                    if (tag === 'STYLE' || tag === 'LINK' || tag === 'SCRIPT' || tag === 'HEAD') continue;
                    if (el.style.display !== 'none') el.style.setProperty('display', 'none', 'important');
                  }
                }
                for (var j = 1; j < chain.length; j++) {
                  var item = chain[j];
                  if (item === document.documentElement || item === document.body) continue;
                  item.style.setProperty('position', 'fixed', 'important');
                  item.style.setProperty('inset', '0', 'important');
                  item.style.setProperty('width', '100vw', 'important');
                  item.style.setProperty('height', '100vh', 'important');
                  item.style.setProperty('margin', '0', 'important');
                  item.style.setProperty('padding', '0', 'important');
                  item.style.setProperty('transform', 'none', 'important');
                  item.style.setProperty('contain', 'none', 'important');
                  item.style.setProperty('z-index', '2147483647', 'important');
                  item.style.setProperty('background', '#000', 'important');
                }
                video.style.setProperty('width', '100vw', 'important');
                video.style.setProperty('height', '100vh', 'important');
                video.style.setProperty('object-fit', 'contain', 'important');
                video.style.setProperty('background', '#000', 'important');
                document.documentElement.style.setProperty('background', '#000', 'important');
                if (document.body) {
                  document.body.style.setProperty('margin', '0', 'important');
                  document.body.style.setProperty('padding', '0', 'important');
                  document.body.style.setProperty('overflow', 'hidden', 'important');
                  document.body.style.setProperty('background', '#000', 'important');
                }
              }
              if (!window.__efirCleanTicks) {
                window.__efirCleanTicks = window.setInterval(clean, 1500);
                window.addEventListener('resize', clean);
              }
              clean();
              return 'ok';
            })()
        """

        private const val GUIDE_RESERVE_DP = 116f
        private const val GUIDE_WIDTH_DP = 420f

        /** Tiles stay readable on a wide screen: past six columns the words become unreadable. */
        private const val MAX_TILE_COLUMNS = 6
        private const val PREV_ALPHA = 0.35f
        private const val NEXT_ALPHA = 0.55f

        /** Closer than this to the end counts as watched: next time it starts from the beginning. */
        private const val FINISH_MARGIN_MS = 90_000L
    }
}

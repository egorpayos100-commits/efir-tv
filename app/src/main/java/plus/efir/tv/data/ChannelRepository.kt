package plus.efir.tv.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream

/**
 * Playlists. A list can come from three places: the addresses typed in settings, the bodies that
 * last worked for those same addresses (kept on disk), and the copy baked into the APK. Network
 * work never happens on the UI thread — the built-in list answers at once, the fetched one
 * replaces it. Every address in the list is merged into one channel table, so a channel that two
 * playlists both carry ends up with two sources instead of two rows.
 */
class ChannelRepository(private val context: Context) {

    enum class Origin { Asset, Cache, Network }

    private val prefs = context.getSharedPreferences("efir", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val queue = LinkedBlockingQueue<Runnable>()
    private val busy = AtomicBoolean(false)

    // Written by the io thread, read by the UI as a finished snapshot.
    @Volatile
    private var cached: List<Channel>? = null
    @Volatile
    private var cachedFilms: List<Channel>? = null

    /** Which source each list really came from, so the settings rows can say it out loud. */
    @Volatile
    var channelsOrigin = Origin.Asset
        private set
    @Volatile
    var filmsOrigin = Origin.Asset
        private set

    /** UI-thread callback after a pull: both lists are already replaced when they were good. */
    var onRemote: (() -> Unit)? = null

    init {
        Thread {
            while (true) runCatching { queue.take().run() }
        }.apply { isDaemon = true; name = "playlist-io" }.start()
    }

    fun channels(): List<Channel> = cached ?: loadChannels().also { cached = it }

    /** Read both tables on a worker thread so the first screen never waits on disk. */
    fun preload() {
        channels()
        films()
    }

    /** Own library: the same playlist format, but nothing zaps or gets a programme guide. */
    fun films(): List<Channel> = cachedFilms ?: loadFilms().also { cachedFilms = it }

    /**
     * Addresses the loaded lists publish for the same channel under the broadcaster-neutral id,
     * used as spares by a channel whose own source is a resolver.
     */
    fun backupSources(ids: List<String>, exceptKey: String): List<Source> {
        if (ids.isEmpty()) return emptyList()
        return channels()
            .filter { it.key != exceptKey }
            .filter { channel ->
                ids.any { id -> channel.key.substringBefore('@').equals(id, ignoreCase = true) }
            }
            .flatMap { it.sources }
            .filter { !it.isResolver && it.url.isNotEmpty() }
            .distinctBy { it.url }
    }

    fun filmByGroup(): List<Pair<String, List<Channel>>> =
        films().groupBy { it.group }.toList().sortedBy { it.first }

    /** Addresses to pull for the live table; no setting means only the list inside the APK. */
    fun channelUrls(): List<String> {
        val saved = prefs.getString(CHANNELS_URLS, null)
        return saved?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() } ?: DEFAULT_PLAYLISTS
    }

    /** True until he edits the setting: the shelf label has to say where the list really comes from. */
    fun usingDefaultPlaylists() = prefs.getString(CHANNELS_URLS, null) == null

    /**
     * The cinema is served from the same host as the live list, so a film he sends me lands on his
     * shelf on the next open — no build, no stick. The copy inside the APK stays as the floor.
     * A saved blank field means «nothing typed», not «no cinema»: the shelf still pulls this address.
     */
    fun filmsUrl(): String =
        prefs.getString(FILMS_URL, null)?.trim().takeUnless { it.isNullOrEmpty() } ?: DEFAULT_FILMS

    /** Hours between playlist pulls. The screen does the pulling, so it only happens while open. */
    fun refreshHours(): Int = prefs.getInt(REFRESH_HOURS, DEFAULT_REFRESH_HOURS)

    fun setRefreshHours(hours: Int) {
        prefs.edit().putInt(REFRESH_HOURS, hours).apply()
    }

    fun channelsUpdatedAt() = prefs.getLong(CHANNELS_UPDATED, 0L)

    fun filmsUpdatedAt() = prefs.getLong(FILMS_UPDATED, 0L)

    /** Saving an empty list puts the live table back on the copy inside the APK. */
    fun setChannelUrls(urls: List<String>) {
        prefs.edit().putString(CHANNELS_URLS, urls.map { it.trim() }.filter { it.isNotEmpty() }
            .joinToString("\n")).apply()
        reload()
    }

    fun setFilmsUrl(url: String) {
        prefs.edit().putString(FILMS_URL, url.trim()).apply()
        reload()
    }

    /** Throw away downloaded bodies and timestamps: the next read starts from the APK copy. */
    fun clearCache() {
        val edit = prefs.edit()
        prefs.all.keys.filter {
            it.startsWith(CHANNELS_TAG) || it == FILMS_TAG ||
                it == CHANNELS_UPDATED || it == FILMS_UPDATED
        }.forEach { edit.remove(it) }
        edit.apply()
        (0 until channelUrls().size + 8).forEach { index ->
            runCatching { File(context.cacheDir, "$CHANNELS_CACHE-$index").delete() }
        }
        runCatching { File(context.cacheDir, FILMS_CACHE).delete() }
        reload()
    }

    /** Forget the loaded lists: the next read goes back to whichever source is configured. */
    fun reload() {
        cached = null
        cachedFilms = null
        if (channelUrls().isEmpty()) channelsOrigin = Origin.Asset
        if (filmsUrl().isEmpty()) filmsOrigin = Origin.Asset
    }

    /**
     * Pulls every playlist off the UI thread. False means nothing was queued: no address typed in,
     * or a pull already running, so the caller can say which of the two the viewer is looking at.
     */
    fun refresh(): Boolean {
        val wanted = channelUrls() + listOf(filmsUrl()).filter { it.isNotEmpty() }
        if (wanted.isEmpty()) return false
        if (busy.getAndSet(true)) return false
        queue.offer(Runnable {
            runCatching { pull() }.onFailure { Log.w(TAG, "playlist pull failed", it) }
            busy.set(false)
            main.post { onRemote?.invoke() }
        })
        return true
    }

    /** Reads every configured address; a list that fails keeps the body that last worked. */
    private fun pull() {
        val lists = channelUrls().mapIndexed { index, url ->
            pullOne(url, File(context.cacheDir, "$CHANNELS_CACHE-$index"), "$CHANNELS_TAG-$index",
                ::parseChannels)
        }
        val pulled = lists.filterNotNull()
        if (pulled.isNotEmpty()) {
            cached = merge(pulled.map { it.first })
            channelsOrigin = combinedOrigin(pulled.map { it.second })
            if (pulled.any { it.second == Origin.Network }) stamp(CHANNELS_UPDATED)
        }
        pullOne(filmsUrl(), File(context.cacheDir, FILMS_CACHE), FILMS_TAG, ::parseFilms)?.let {
            (list, origin) ->
            cachedFilms = mergeFilms(builtInFilms(), list)
            filmsOrigin = origin
            if (origin == Origin.Network) stamp(FILMS_UPDATED)
        }
    }

    /** Live list only when the URL or its last good body produced something playable. */
    private fun pullOne(
        url: String,
        file: File,
        tagKey: String,
        parse: (String) -> List<Channel>,
    ): Pair<List<Channel>, Origin>? {
        if (!url.startsWith("http")) return null
        val body = get(url)
        if (body != null && body.contains("#EXTM3U")) {
            runCatching {
                file.writeText(body)
                prefs.edit().putString(tagKey, url.tag()).apply()
            }
            parse(body).takeIf { it.isNotEmpty() }?.let { return it to Origin.Network }
        }
        // A stored body belongs to the URL that produced it, never to whatever replaced it.
        val stored = runCatching {
            if (file.exists() && prefs.getString(tagKey, null) == url.tag()) file.readText() else null
        }.getOrNull()
        return stored?.let { parse(it) }?.takeIf { it.isNotEmpty() }?.let { it to Origin.Cache }
    }

    /**
     * Several playlists name the same channel differently, so merging happens by address first and
     * by name second. Numbers follow the merged order, which is the order of the remote lists.
     * The copy inside the APK is merged first: a remote list may add addresses to his channels,
     * it may never stand in for them and empty his shelves.
     */
    private fun merge(lists: List<List<Channel>>): List<Channel> {
        val byKey = LinkedHashMap<String, Channel>()
        val byName = HashMap<String, String>()
        val seenUrl = HashMap<String, String>()
        var number = 0
        for (list in listOf(loadChannels()) + lists) {
            for (channel in list) {
                val key = channel.key.trim().lowercase()
                // A fetched list rarely spells the id the way his own file does, and a row that only
                // differs in spelling used to land next to the real one without a programme id: the
                // shelf then showed two «Animal Planet», one of them silent. The name decides.
                val name = channel.name.trim().lowercase().filter(Char::isLetterOrDigit)
                val known = byKey[key] ?: byName[name]?.let { byKey[it] }
                // Resolver rows have no address, so the address-dedupe must not eat them.
                val fresh = channel.sources.filter { it.isResolver || seenUrl.put(it.url, key) == null }
                if (known == null) {
                    if (fresh.isEmpty()) continue
                    byKey[key] = channel.copy(number = ++number, sources = fresh)
                    if (name.isNotEmpty()) byName.putIfAbsent(name, key)
                } else {
                    if (fresh.isEmpty()) continue
                    byKey[known.key.trim().lowercase()] = known.copy(sources = known.sources + fresh)
                }
            }
        }
        return byKey.values.toList()
    }

    private fun combinedOrigin(origins: List<Origin>): Origin = when {
        origins.any { it == Origin.Network } -> Origin.Network
        origins.any { it == Origin.Cache } -> Origin.Cache
        else -> Origin.Asset
    }

    private fun stamp(key: String) = prefs.edit().putLong(key, System.currentTimeMillis()).apply()

    /**
     * Follows redirects between http and https too: HttpURLConnection stops at a scheme change,
     * and several broadcasters answer plain HTTP only by bouncing to their CDN over TLS.
     */
    private fun get(address: String): String? {
        var url = URL(nocache(address))
        repeat(MAX_REDIRECTS) {
            val connection = open(url) ?: return null
            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    val target = connection.getHeaderField("Location") ?: return null
                    url = URL(url, target)
                    return@repeat
                }
                if (code != 200) return null
                // A playlist is text: a few megabytes in means the address is not one.
                return readBody(connection)
            } catch (error: Exception) {
                Log.w(TAG, "playlist fetch failed: $url", error)
                return null
            } finally {
                connection.disconnect()
            }
        }
        return null
    }

    /**
     * The site is dialled by its own edge address first: the provider's DNS answer for it is a
     * block page, and asking the router at all is what made the pull fail.
     */
    private fun open(url: URL): HttpURLConnection? {
        for (ip in Doh.dial(url.host)) {
            val connection = runCatching { url.openConnection() as HttpURLConnection }
                .getOrNull() ?: continue
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept-Encoding", "gzip")
            connection.setRequestProperty("Cache-Control", "no-cache, no-store")
            Doh.pin(connection, ip)
            if (runCatching { connection.responseCode }.getOrDefault(0) != 0) return connection
            connection.disconnect()
        }
        return null
    }

    /**
     * A playlist edited in place must not come back as last week's copy, so the fetch is stamped.
     * An address that already carries a query is left byte-for-byte: signed lists check it.
     */
    private fun nocache(address: String): String =
        if ('?' in address) address else "$address?${System.currentTimeMillis()}"

    /** Reads at most MAX_BYTES, so a mistaken link to a video file cannot fill the heap. */
    private fun readBody(connection: HttpURLConnection): String? {
        val stream = if (connection.contentEncoding == "gzip")
            GZIPInputStream(connection.inputStream) else connection.inputStream
        return stream.buffered().use { input ->
            val out = ByteArrayOutputStream(1 shl 16)
            val buffer = ByteArray(8192)
            var total = 0
            while (total < MAX_BYTES) {
                val read = input.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                total += read
            }
            String(out.toByteArray(), Charsets.UTF_8)
        }
    }

    private fun parseChannels(text: String): List<Channel> =
        M3uParser.parse(text).mapIndexed { index, channel -> channel.copy(number = index + 1) }

    private fun parseFilms(text: String): List<Channel> =
        M3uParser.parse(text, M3uParser.FILM_GROUP).mapIndexed { index, channel ->
            // Prefixed so a film named like a channel cannot steal its favourite star.
            channel.copy(key = "film:${channel.key}", number = index + 1, kind = Kind.Film)
        }

    private fun readAsset(vararg names: String): String? =
        names.firstNotNullOfOrNull { name ->
            runCatching { context.assets.open(name).bufferedReader().use { it.readText() } }.getOrNull()
        }

    private fun loadChannels(): List<Channel> {
        val text = readAsset(PREFERS, FALLBACK) ?: return emptyList()
        // Playlist order is the channel numbering the viewer expects, so numbers follow it.
        return parseChannels(text)
    }

    /**
     * His own cinema lives inside the APK and is always on the rail. A films address, when he typed
     * one, only adds to it: replacing the shelf with a remote list is what would make his films vanish.
     */
    private fun loadFilms(): List<Channel> = mergeFilms(builtInFilms(), filmsFromDisk())

    private fun builtInFilms(): List<Channel> =
        readAsset(FILMS)?.let(::parseFilms).orEmpty()

    /** The last good body of the films address, so the shelf does not blink while a pull runs. */
    private fun filmsFromDisk(): List<Channel> {
        val url = filmsUrl()
        if (url.isEmpty()) return emptyList()
        val file = File(context.cacheDir, FILMS_CACHE)
        val text = runCatching {
            if (file.exists() && prefs.getString(FILMS_TAG, null) == url.tag()) file.readText()
            else null
        }.getOrNull() ?: return emptyList()
        return parseFilms(text)
    }

    /**
     * The address is the shelf: its rows are shown in its order, and it already lists newest
     * additions first. So a pull can rearrange the wall without a new APK. The films baked into
     * the APK only fill the gaps and stay behind, so nothing he already had can vanish because
     * the address lost a row.
     */
    private fun mergeFilms(builtIn: List<Channel>, extra: List<Channel>): List<Channel> {
        if (extra.isEmpty()) return builtIn
        val extraKeys = extra.mapTo(HashSet()) { it.key }
        val extraUrls = extra.flatMapTo(HashSet()) { channel -> channel.sources.map { it.url } }
        val lost = builtIn.filter { channel ->
            channel.key !in extraKeys && channel.sources.none { it.url in extraUrls }
        }
        return (extra + lost).mapIndexed { index, channel ->
            channel.copy(number = index + 1)
        }
    }

    private fun String.tag() = hashCode().toString()

    companion object {
        /**
         * His own list, kept at an address I rewrite when a signed link dies: the box then picks up
         * the new links by itself, with no stick and no new APK. Saving an empty playlist field turns
         * this off and goes back to the copy inside the APK. It is his file, not a public catalogue —
         * those replaced his list on the box and buried it under dead rows.
         */
        val DEFAULT_PLAYLISTS =
            listOf("https://raw.githubusercontent.com/egorpayos100-commits/tvlist/main/efir-5be13734fc5b.m3u")

        /** His cinema: I append a row here when he sends a film, and the shelf picks it up by itself. */
        private const val DEFAULT_FILMS =
            "https://raw.githubusercontent.com/egorpayos100-commits/tvlist/main/efir-films.m3u"

        private const val TAG = "EfirPlaylist"
        private const val PREFERS = "channels.m3u"
        private const val FALLBACK = "channels.example.m3u"
        private const val FILMS = "films.m3u"
        private const val CHANNELS_URLS = "channels_urls"
        private const val FILMS_URL = "films_url"
        private const val CHANNELS_CACHE = "remote_channels.m3u"
        private const val FILMS_CACHE = "remote_films.m3u"
        private const val CHANNELS_TAG = "channels_url_tag"
        private const val FILMS_TAG = "films_url_tag"
        private const val CHANNELS_UPDATED = "channels_updated"
        private const val FILMS_UPDATED = "films_updated"
        private const val REFRESH_HOURS = "refresh_hours"
        private const val DEFAULT_REFRESH_HOURS = 6
        private const val MAX_BYTES = 6_000_000
        private const val MAX_REDIRECTS = 5
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 20_000
        private const val USER_AGENT = "Mozilla/5.0 (Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
    }
}

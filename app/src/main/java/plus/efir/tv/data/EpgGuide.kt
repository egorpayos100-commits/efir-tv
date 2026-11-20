package plus.efir.tv.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPInputStream

data class Programme(val start: Long, val end: Long, val title: String)

data class Slots(val prev: Programme?, val now: Programme?, val next: Programme?) {
    val isEmpty: Boolean get() = prev == null && now == null && next == null

    fun elapsedFraction(now: Long): Float {
        val item = this.now ?: return 0f
        if (item.end <= item.start) return 0f
        return ((now - item.start).toFloat() / (item.end - item.start)).coerceIn(0f, 1f)
    }
}

/**
 * Programme guide from the free XMLTV dump of Russian-language channels (epg.one / it999).
 * The dump is ~19 MB compressed, so it is fetched on a worker thread, reduced to the channels
 * this playlist actually uses, and kept as a line cache that survives restarts.
 */
class EpgGuide(private val context: Context) {

    private val cacheFile = File(context.cacheDir, "epg.txt")

    /** Rows for the couple of channels the dump keeps losing, kept apart so their own age governs them. */
    private val reserveFile = File(context.cacheDir, "epg_reserve.txt")
    private val main = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("efir", Context.MODE_PRIVATE)

    // Replaced wholesale by the worker thread; the UI only ever reads a finished map.
    @Volatile
    private var byId: Map<String, List<Programme>> = emptyMap()

    @Volatile
    private var fetching = false

    /** Ids of the guide rows the loaded playlist uses; the fetch is not tied to what is on screen. */
    @Volatile
    private var fetchingIds: Set<String> = emptySet()

    /**
     * The dump is ~20 MB and holds the pipe for a minute on this box. While the air is buffering we
     * stop reading and wait; the air always wins. It used to be a kill: every zap threw the whole
     * half-read file away, and because a zap follows a zap he never got a schedule at all.
     */
    @Volatile
    private var pauseUntil = 0L

    /** The schedule baked into the APK, kept only so a bad download cannot shorten it. */
    @Volatile
    private var seed: Map<String, List<Programme>> = emptyMap()

    /** A cancel stays for the URL box only, where the old feed really is worthless. */
    private var generation = 0

    /** The dump did not arrive, or arrived with nothing in it. Wording belongs to the UI. */
    @Volatile
    private var failure: String? = null

    /** Bytes of the last read attempt, so a hung download is visible as a number that stops growing. */
    @Volatile
    private var lastBytes = 0L

    var onLoaded: (() -> Unit)? = null

    /** Lets the stream have the network, then pick the same read back up. */
    fun holdPipe(ms: Long) {
        val until = System.currentTimeMillis() + ms
        if (until > pauseUntil) pauseUntil = until
    }

    /**
     * What the guide did, phrased for one toast. Null only while the very first fetch is still
     * running with no cache on disk — there is nothing honest to say about it yet.
     */
    fun statusText(): String? {
        val read = lastBytes
        if (failure == null && fetching && read > 0) return "программа: читаю ${(read / 1_048_576) + 1} МБ"
        if (byId.isNotEmpty()) return null
        failure?.let { return "программа: $it" }
        return if (fetching) "программа: читаю…" else null
    }

    /** The XMLTV dump to read: blank switches the programme strip off. */
    fun url(): String = prefs.getString(FEED_KEY, DEFAULT_FEED).orEmpty()

    fun setUrl(value: String) {
        prefs.edit().putString(FEED_KEY, value.trim()).apply()
        runCatching { cacheFile.delete() }
        runCatching { reserveFile.delete() }
        generation++
        failure = null
        byId = emptyMap()
        main.post { onLoaded?.invoke() }
    }

    /** Remembered for the next fetch; calling it does not start a download by itself. */
    fun useIds(ids: Set<String>) {
        fetchingIds = ids
    }

    /**
     * How many of these ids really have rows in memory right now. The guide shelf prints it in its
     * title: «программа 0» is an empty table, «программа 26» with blank tiles is something else.
     */
    fun coverage(ids: Set<String>): Int = ids.count { (byId[it]?.size ?: 0) > 0 }

    /**
     * Both tables are disk reads and the screen must not wait on either: the copy inside the APK
     * answers for every channel the download has not reached, so the strip is never blank because
     * a 20 MB file did not arrive over the operator's wifi.
     */
    fun loadCache() {
        val from = System.currentTimeMillis() - KEEP_BEFORE
        readSeed(from)
        // Age is the download's business, not the read's: every row carries its own timestamps,
        // so a file from yesterday still names the programmes airing today and simply stops
        // short. Refusing it here used to blank the strip for hours whenever the pull lagged.
        val loaded = readRows(cacheFile, from)
        readRows(reserveFile, from).forEach { (id, rows) ->
            val mine = loaded.getOrPut(id) { ArrayList() }
            rows.forEach { row ->
                // Only where the dump has nothing: its fresher title always wins.
                if (mine.none { it.start < row.end && it.end > row.start }) mine.add(row)
            }
        }
        byId = withFloor(sorted(loaded))
    }

    /** Rows of one cache file; a broken line is simply not a row. */
    private fun readRows(file: File, from: Long): HashMap<String, ArrayList<Programme>> {
        val out = HashMap<String, ArrayList<Programme>>()
        runCatching {
            if (!file.exists()) return out
            file.forEachLine { line ->
                val parts = line.split('\t')
                if (parts.size < 4) return@forEachLine
                val start = parts[1].toLongOrNull() ?: return@forEachLine
                val end = parts[2].toLongOrNull() ?: return@forEachLine
                if (end > from) out.getOrPut(parts[0]) { ArrayList() }.add(Programme(start, end, parts[3]))
            }
        }
        return out
    }

    /** Rows for his channels, cut out of the dump on the desk and shipped in the APK. */
    private fun readSeed(from: Long) {
        val into = HashMap<String, ArrayList<Programme>>()
        runCatching {
            context.assets.open(SEED).bufferedReader().use { reader ->
                reader.lineSequence().forEach { line ->
                    val parts = line.split('\t')
                    if (parts.size < 4) return@forEach
                    val start = parts[1].toLongOrNull() ?: return@forEach
                    val end = parts[2].toLongOrNull() ?: return@forEach
                    if (end > from) into.getOrPut(parts[0]) { ArrayList() }.add(Programme(start, end, parts[3]))
                }
            }
        }
        seed = sorted(into)
    }

    /**
     * The shipped copy is the floor: the downloaded guide wins inside every slot it really covers,
     * and the APK copy fills every hole it left. Before this a download that arrived short (or a
     * half-written cache) replaced the whole table, and a shelf of his channels went blank while the
     * copy inside the APK had the evening all along.
     */
    private fun withFloor(source: Map<String, List<Programme>>): Map<String, List<Programme>> {
        if (seed.isEmpty()) return source
        val out = HashMap<String, ArrayList<Programme>>()
        source.forEach { (id, rows) -> out[id] = ArrayList(rows) }
        seed.forEach { (id, rows) ->
            val mine = out.getOrPut(id) { ArrayList() }
            rows.forEach { held ->
                // Only a hole: a slot the network did reach keeps its fresher title.
                if (mine.none { it.start < held.end && it.end > held.start }) mine.add(held)
            }
        }
        return out.mapValues { (_, list) -> list.sortedBy { it.start } }
    }

    /**
     * The whole guide is one file: the set of channel ids only selects what is stored, so an empty
     * set here would throw the strip away and re-download 19 MB on the next tick.
     */
    fun refresh() {
        if (fetching) return
        if (url().isBlank()) {
            failure = "выключена"
            return
        }
        val started = System.currentTimeMillis()
        val dumpFresh = cacheFile.exists() && age() < MAX_AGE_MS && engineFresh()
        val reserveFresh = reserveFile.exists() &&
            started - reserveFile.lastModified() < RESERVE_AGE_MS
        // Only a channel that has nothing on air right now is worth its small page.
        val needReserve = idsWithoutGuide()
        val reserveDue = needReserve.isNotEmpty() && !reserveFresh &&
            started - lastReserveAttempt >= RESERVE_RETRY_MS
        if (dumpFresh && !reserveDue) return
        // Without ids there is nothing to keep out of the dump, and an empty result used to look
        // like a broken guide.
        if (fetchingIds.isEmpty()) return
        // The ticker asks every 15 s: without this a dump that keeps dying mid-read would pull
        // 20 MB over and over and the box would never get its network back.
        if (started - lastAttempt < RETRY_MS) return
        lastAttempt = started
        if (reserveDue) lastReserveAttempt = started
        fetching = true
        val gen = generation
        Thread {
            failure = null
            val outcome = if (dumpFresh) null else runCatching { fetch(fetchingIds, gen) }
            // Small pages of their own: fetched whenever they are a day or an hour old, even if
            // the big dump is fine, because the dump is exactly what left these channels blank.
            val extra = if (reserveDue) {
                runCatching { fetchReserve(gen, needReserve) }.getOrDefault(emptyMap())
            } else emptyMap()
            fetching = false
            // Only a result from the current feed counts; a cancelled read leaves the old cache alone.
            if (gen == generation) {
                val done = outcome?.getOrNull()?.takeIf { it.isNotEmpty() }
                if (done != null) {
                    writeCache(done, cacheFile)
                    prefs.edit().putInt(ENGINE_KEY, ENGINE).apply()
                    failure = null
                } else if (outcome != null) {
                    failure = outcome.exceptionOrNull()?.let { it.javaClass.simpleName }
                        ?: failure
                        ?: "в файле нет этих каналов"
                }
                if (extra.isNotEmpty()) writeCache(extra, reserveFile)
                // Rebuilt from both files so the strip shows the new rows without a restart.
                loadCache()
                main.post { onLoaded?.invoke() }
            }
        }.apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    /** When the last download started, so a failing one cannot re-pull 20 MB every tick. */
    @Volatile
    private var lastAttempt = 0L

    /** When the small pages were last tried, so a site that is down is not hit every three minutes. */
    @Volatile
    private var lastReserveAttempt = 0L

    /**
     * 1.11 rebuilt the table once: before it a short read could leave whole channels out of the
     * cache for six hours. The first pull after this install is therefore always a full one.
     */
    private fun engineFresh(): Boolean = prefs.getInt(ENGINE_KEY, 0) >= ENGINE

    /** Ids that have a reserve page of their own and nothing on air right now. */
    private fun idsWithoutGuide(): Set<String> {
        val now = System.currentTimeMillis()
        return RESERVE.keys.filterTo(HashSet()) { id ->
            fetchingIds.contains(id) && byId[id]?.any { it.start <= now && it.end > now } != true
        }
    }

    fun slots(epgId: String?, now: Long): Slots {
        val list = epgId?.let { byId[it] } ?: return Slots(null, null, null)
        val index = list.indexOfFirst { it.end > now }
        // Nothing in the file reaches the present: the guide is old, not quiet. Showing its last
        // row anyway is what made the strip claim a night programme at four in the afternoon.
        if (index < 0) return Slots(null, null, null)
        val current = list[index].takeIf { it.start <= now }
        return if (current == null) {
            Slots(list.getOrNull(index - 1), null, list[index])
        } else {
            Slots(list.getOrNull(index - 1), current, list.getOrNull(index + 1))
        }
    }

    private fun age(): Long = System.currentTimeMillis() - cacheFile.lastModified()

    private fun sorted(source: Map<String, List<Programme>>): Map<String, List<Programme>> =
        source.mapValues { (_, list) -> list.sortedBy { it.start } }

    /**
     * Written aside and moved into place: a write cut short used to leave the schedule without its
     * tail, and the channels that lost their hours looked like channels without a guide.
     *
     * The new rows are merged into what the file already holds instead of replacing it. A pull that
     * came back with fewer channels (or a playlist that loaded half of them at that moment) used to
     * wipe the whole table, and a channel with no rows is exactly «Нет программы» on the box.
     */
    private fun writeCache(parsed: Map<String, List<Programme>>, target: File) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        runCatching {
            val from = System.currentTimeMillis() - KEEP_BEFORE
            val merged = readRows(target, from)
            parsed.forEach { (id, list) ->
                val held = merged.getOrPut(id) { ArrayList() }
                list.forEach { row ->
                    held.removeAll { it.start < row.end && it.end > row.start }
                    held.add(row)
                }
            }
            if (merged.isEmpty()) return@runCatching
            tmp.bufferedWriter().use { writer ->
                merged.forEach { (id, list) ->
                    list.forEach {
                        writer.write("$id\t${it.start}\t${it.end}\t${oneLine(it.title)}\n")
                    }
                }
            }
            target.delete()
            tmp.renameTo(target)
        }
    }

    private fun oneLine(title: String): String =
        title.replace('\t', ' ').replace('\n', ' ').trim()

    private fun fetch(ids: Set<String>, gen: Int): Map<String, List<Programme>> {
        val now = System.currentTimeMillis()
        val from = now - KEEP_BEFORE
        val to = now + KEEP_AFTER
        var url = URL(url()).also { if (it.protocol !in HTTP) return emptyMap() }
        repeat(MAX_REDIRECTS) {
            if (gen != generation) return emptyMap()
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 15_000
                // A paused read must stay inside this window, or the socket gives up on us: the
                // streamer only ever holds the pipe for PIPE_HOLD_MS at a time.
                connection.readTimeout = 60_000
                connection.instanceFollowRedirects = false
                // The dump is rewritten in place every night. A cached copy of yesterday's file is
                // what left a channel with no «что будет» while the APK copy had it.
                connection.useCaches = false
                connection.setRequestProperty("Cache-Control", "no-cache, no-store")
                connection.setRequestProperty("User-Agent", USER_AGENT)
                val code = connection.responseCode
                if (code in 301..308) {
                    val target = connection.getHeaderField("Location") ?: return emptyMap()
                    url = URL(url, target)
                    return@repeat
                }
                if (code != 200) {
                    failure = "ответ $code"
                    return emptyMap()
                }
                lastBytes = 0
                return connection.inputStream.buffered(1 shl 16).use { raw ->
                    GZIPInputStream(Counted(raw)).use { parse(it, ids, from, to, gen) }
                }
            } finally {
                connection.disconnect()
            }
        }
        return emptyMap()
    }

    /**
     * Counts the compressed bytes so the screen can say how far the read actually got, and spends
     * the read: the dump used to take the pipe as fast as the operator's wifi could give it, which
     * is the stutter he already complained about. One short sleep per 64 KB block keeps this under
     * ~300 КБ/с — a live SD stream needs 220 — and a real starvation still pauses us outright.
     */
    private inner class Counted(private val source: java.io.InputStream) : java.io.FilterInputStream(source) {
        override fun read(buffer: ByteArray, offset: Int, count: Int): Int {
            val got = source.read(buffer, offset, count)
            if (got > 0) {
                val before = lastBytes / READ_PACE_BLOCK
                lastBytes += got
                if (lastBytes / READ_PACE_BLOCK > before) runCatching { Thread.sleep(READ_PACE_MS) }
            }
            return got
        }
    }

    /** The air owns the network while it buffers; this read waits instead of being thrown away. */
    private fun waitForStream(gen: Int): Boolean {
        if (gen != generation) return false
        while (System.currentTimeMillis() < pauseUntil) {
            if (gen != generation) return false
            runCatching { Thread.sleep(PAUSE_SLICE_MS) }
        }
        return gen == generation
    }

    private fun parse(
        input: java.io.InputStream,
        wanted: Set<String>,
        from: Long,
        to: Long,
        gen: Int,
    ): Map<String, List<Programme>> {
        val result = HashMap<String, ArrayList<Programme>>()
        val format = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
        val parser = Xml.newPullParser()
        parser.setInput(input, null)
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            // Checked per element: that is how the socket is handed back to the stream within a
            // few milliseconds instead of after the whole 200 MB of XML.
            if (!waitForStream(gen)) return emptyMap()
            if (event == XmlPullParser.START_TAG && parser.name == "programme") {
                val channel = parser.getAttributeValue(null, "channel")
                if (channel != null && wanted.contains(channel)) {
                    val start = format.parseTime(parser.getAttributeValue(null, "start"))
                    val stop = format.parseTime(parser.getAttributeValue(null, "stop"))
                    val title = readTitle(parser)
                    if (start != null && stop != null && title != null && stop > from && start < to) {
                        result.getOrPut(channel) { ArrayList() }.add(Programme(start, stop, title))
                    }
                }
            }
            event = parser.next()
        }
        return result
    }

    /** Steps over the children of the current <programme> and keeps the first <title>. */
    private fun readTitle(parser: XmlPullParser): String? {
        val depth = parser.depth
        var title: String? = null
        while (true) {
            when (parser.next()) {
                XmlPullParser.START_TAG ->
                    if (title == null && parser.name == "title") title = parser.nextText()
                XmlPullParser.END_TAG -> if (parser.depth == depth) return title
                XmlPullParser.END_DOCUMENT -> return title
            }
        }
    }

    /**
     * The channels the dump keeps dropping. The reserve is the day pages of a schedule site:
     * small HTML, no API, and it prints only HH:MM, so the date comes from Moscow's today.
     */
    private fun fetchReserve(gen: Int, ids: Set<String>): Map<String, List<Programme>> {
        val wanted = RESERVE.filterKeys { ids.contains(it) }
        if (wanted.isEmpty()) return emptyMap()
        val out = HashMap<String, ArrayList<Programme>>()
        wanted.forEach { (id, slug) ->
            val byStart = LinkedHashMap<Long, Programme>()
            RESERVE_DAYS.forEachIndexed { offset, page ->
                if (gen != generation) return emptyMap()
                val html = runCatching { readText("$RESERVE_BASE/$slug/$page/") }.getOrNull().orEmpty()
                parseReserve(html, offset).forEach { row ->
                    // The page for tomorrow wins a tie: the trailing rows of today's page already
                    // belong to tomorrow's morning.
                    byStart[row.start] = row
                }
            }
            if (byStart.isEmpty()) return@forEach
            val rows = byStart.values.sortedBy { it.start }
            out[id] = ArrayList(rows.mapIndexed { index, row ->
                val end = rows.getOrNull(index + 1)?.start ?: (row.start + RESERVE_LAST_MS)
                row.copy(end = end.coerceAtLeast(row.start + 60_000L))
            })
        }
        return out
    }

    /** Rows of one page: each begins with pItem, carry a HH:MM and a title; the day rolls where the clock does. */
    private fun parseReserve(html: String, dayOffset: Int): List<Programme> {
        val from = html.indexOf(DETAILED)
        if (from < 0) return emptyList()
        // The recommendation cards after the schedule use the same markup but belong to other channels.
        val to = html.indexOf(PREVIEW, from).let { if (it < 0) html.length else it }
        val block = html.substring(from, to)
        val day = Calendar.getInstance(MSK).apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_MONTH, dayOffset)
        }
        val out = ArrayList<Programme>()
        var previous = -1
        var night = 0
        block.split(ROW).drop(1).forEach { piece ->
            val time = TIME.find(piece) ?: return@forEach
            val minutes = time.groupValues[1].toInt() * 60 + time.groupValues[2].toInt()
            if (minutes < previous) night++
            previous = minutes
            val primary = PRIMARY.find(piece)?.groupValues?.get(1)?.let(::unescape)?.trim().orEmpty()
            if (primary.isEmpty()) return@forEach
            val secondary = SECONDARY.find(piece)?.groupValues?.get(1)?.let(::unescape)?.trim().orEmpty()
            val title = listOf(primary, secondary)
                .filter { it.isNotEmpty() }
                .joinToString(" ")
                .replace(SPACES, " ")
                .trim()
            val start = day.timeInMillis + night * DAY_MS + minutes * 60_000L
            out.add(Programme(start, start + RESERVE_LAST_MS, title))
        }
        return out
    }

    private fun readText(address: String): String {
        val connection = URL(address).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.useCaches = false
            connection.setRequestProperty("Cache-Control", "no-cache, no-store")
            connection.setRequestProperty("User-Agent", USER_AGENT)
            if (connection.responseCode != 200) return ""
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun unescape(value: String): String = value
        .replace("&quot;", "\"")
        .replace("&#039;", "'")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")

    companion object {
        private const val FEED_KEY = "epg_url"
        private const val DEFAULT_FEED = "http://epg.one/ru2.xml.gz"

        /** His channels' schedule, cut out of the dump on the desk: 136 KB, always readable. */
        private const val SEED = "epg_seed.txt"
        private val HTTP = setOf("http", "https")
        private const val USER_AGENT = "Mozilla/5.0 (Android TV)"
        private const val MAX_AGE_MS = 6 * 60 * 60 * 1000L
        private const val RETRY_MS = 3 * 60 * 1000L

        /**
         * Channels that have a page of their own on the reserve schedule site: epg-id -> slug.
         * The pages are pulled only for a channel that has nothing on air, so the list may hold
         * every channel the site knows — a page that is never needed costs nothing.
         */
        private val RESERVE = mapOf(
            "146" to "kanal_pervyj-kanal",
            "127" to "kanal_tv-1000",
            "125" to "kanal_tv-1000-action",
            "267" to "kanal_tv-1000-russkoe-kino",
            "2754" to "kanal_cinema",
            "352" to "kanal_evrokino",
            "542" to "kanal_kinohit",
            "644" to "kanal_kinomiks",
            "2020" to "kanal_dom-kino-premium",
            "365" to "kanal_animal-planet",
            "325" to "kanal_discovery",
            "102" to "kanal_national-geographic",
            "223" to "kanal_nat-geo-wild",
            "1394" to "kanal_history",
            "2356" to "kanal_history2",
            "521" to "kanal_viasat-explore",
            "765" to "kanal_viasat-nature",
            "277" to "kanal_viasat-history",
            "2419" to "kanal_ohota-i-rybalka",
            "1681" to "kanal_ohotnik-i-rybolov",
            "1649" to "kanal_tnt4",
            "162" to "kanal_ntv",
            "353" to "kanal_tnt",
            "405" to "kanal_zvezda",
            "187" to "kanal_kultura",
            "4678" to "kanal_sportivnyj",
            "2055" to "kanal_viasat-sport",
            "2052" to "kanal_eurosport-1",
        )
        private const val RESERVE_BASE = "https://programma-peredach.com"
        private val RESERVE_DAYS = listOf("na_segodnya", "na_zavtra")
        private const val RESERVE_AGE_MS = 60 * 60 * 1000L
        private const val RESERVE_RETRY_MS = 20 * 60 * 1000L
        private const val RESERVE_LAST_MS = 60 * 60 * 1000L

        /** Bumped when the cache format or the writer changed; one full pull follows the install. */
        private const val ENGINE_KEY = "epg_engine"
        private const val ENGINE = 2
        private const val DAY_MS = 24 * 60 * 60 * 1000L
        private val MSK: TimeZone = TimeZone.getTimeZone("Europe/Moscow")
        private const val DETAILED = "class=\"pItems detailed\""
        private const val PREVIEW = "class=\"pItems preview\""
        private val ROW = Regex("<div class=\"pItem\\b")
        /** The schedule site prints the hour as `time` on some channels and `titleTime` on others. */
        private val TIME = Regex("class=\"(?:titleTime|time)\">\\s*(\\d{1,2}):(\\d{2})")
        private val PRIMARY = Regex("class=\"primary\">([^<]*)")
        private val SECONDARY = Regex("class=\"secondary\">([^<]*)")
        private val SPACES = Regex("\\s+")
        private const val KEEP_BEFORE = 12 * 60 * 60 * 1000L
        private const val KEEP_AFTER = 72 * 60 * 60 * 1000L
        private const val MAX_REDIRECTS = 4
        private const val PAUSE_SLICE_MS = 250L

        /** The read sleeps this often for this many bytes: ~300 КБ/с, so the air keeps its bandwidth. */
        private const val READ_PACE_BLOCK = 1L shl 16
        private const val READ_PACE_MS = 200L
    }
}

/** SimpleDateFormat is not thread-safe; a broken stamp simply means no guide line. */
private fun SimpleDateFormat.parseTime(value: String?): Long? =
    value?.let { runCatching { parse(it)?.time }.getOrNull() }

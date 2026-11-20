package plus.efir.tv.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import plus.efir.tv.player.PlaybackLog
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Which addresses really answer. Every source of every channel is fetched a little — headers plus
 * the first kilobyte — on a small pool of threads, and the verdict is kept on disk, so the list
 * opens instantly and dead channels sink to the bottom in grey.
 */
class SourceHealth(context: Context) {

    enum class State { Alive, Dead, Unknown }

    private val cacheFile = File(context.cacheDir, "health.txt")

    /** The manual pass and the zap guard both dump verdicts; one of them may write at a time. */
    private val disk = Any()
    private val main = Handler(Looper.getMainLooper())
    private val pool: ExecutorService = Executors.newFixedThreadPool(THREADS) { task ->
        Thread(task, "channel-check").apply { isDaemon = true }
    }
    private val running = AtomicBoolean(false)
    private val tasks = Collections.synchronizedList(ArrayList<Future<*>>())
    private val done = AtomicInteger(0)
    private val total = AtomicInteger(0)

    /** Filled key by key from the pool; copying a map of 1300 URLs per verdict is what made the box stall. */
    private val verdicts = ConcurrentHashMap<String, State>()

    @Volatile
    private var checkedAt = 0L

    /** Names of what a pass found alive, so the log gets one summary line instead of thousands. */
    private val aliveNames = Collections.synchronizedList(ArrayList<String>())

    /** Channels of the last pass, kept so the summary line needs no re-scan. */
    private var probed: List<Channel> = emptyList()

    /** Bumped by every pass and by cancel(); old threads then drop their work. Main thread writes it, pool reads. */
    @Volatile
    private var generation = 0

    /** Bumped by forget(): a zap guard that finished after it must not put the verdicts back. */
    @Volatile
    private var wiped = 0L

    /** Fires on the main thread as rows get their verdict and when a pass ends. */
    var onUpdated: (() -> Unit)? = null

    val isRunning: Boolean get() = running.get()

    fun stateOf(url: String): State = verdicts[url] ?: State.Unknown

    /** A channel is alive when any of its addresses answers; dead only when every one failed. */
    fun stateOf(channel: Channel): State {
        // A resolver row has no address to knock on, so it is never judged dead by the playlist.
        val probes = channel.sources.filter { !it.isResolver && it.url.isNotEmpty() }
        if (probes.isEmpty()) return State.Unknown
        var unknown = false
        for (source in probes) {
            if (SourceStamps.isExpired(source.url)) continue
            when (stateOf(source.url)) {
                State.Alive -> return State.Alive
                State.Unknown -> unknown = true
                State.Dead -> Unit
            }
        }
        return if (unknown) State.Unknown else State.Dead
    }

    fun counts(): Pair<Int, Int> {
        var alive = 0
        probed.forEach { if (stateOf(it) == State.Alive) alive++ }
        return alive to probed.size
    }

    /**
     * Probes the table on the pool. Verdicts younger than KEEP_MS are reused, and a pass already
     * running is dropped instead of stacked on top of it.
     */
    fun check(channels: List<Channel>, force: Boolean = false) {
        if (channels.isEmpty() || running.getAndSet(true)) return
        probed = channels
        dropUnknown(channels)
        val now = System.currentTimeMillis()
        val fresh = !force && checkedAt > 0 && now - checkedAt < KEEP_MS
        val todo = channels.filter { channel ->
            channel.sources.any { !it.isResolver && it.url.isNotEmpty() } &&
                !(fresh && channel.sources.any { verdicts.containsKey(it.url) })
        }
        if (todo.isEmpty()) {
            running.set(false)
            publish()
            return
        }
        val batch = todo.take(MAX_PER_PASS)
        done.set(0)
        total.set(batch.size)
        val pass = ++generation
        tasks.clear()
        batch.forEach { channel -> tasks.add(pool.submit { probe(channel, pass) }) }
    }

    /**
     * Addresses rotate on every playlist pull, so verdicts are kept only for the links the current
     * table really uses. Without this the map and its file grow with every dead link the box saw.
     */
    private fun dropUnknown(channels: List<Channel>) {
        if (verdicts.isEmpty()) return
        val used = HashSet<String>(channels.sumOf { it.sources.size } + 16)
        channels.forEach { channel -> channel.sources.forEach { used.add(it.url) } }
        val stale = ArrayList<String>()
        for (url in verdicts.keys) if (!used.contains(url)) stale.add(url)
        if (stale.isEmpty()) return
        stale.forEach { verdicts.remove(it) }
        writeCache()
    }

    fun cancel() {
        generation++
        tasks.forEach { it.cancel(false) }
        tasks.clear()
        running.set(false)
    }

    /** How far the current pass got, for the «проверяю N из M» line. */
    fun progress(): String {
        val all = total.get()
        return if (all == 0) "0" else "${done.get()}/$all"
    }

    fun forget() {
        cancel()
        wiped++
        verdicts.clear()
        checkedAt = 0L
        probed = emptyList()
        runCatching { cacheFile.delete() }
        publish()
    }

    /** Rescues a zap without waiting for the manual pass, so it must not queue behind it. */
    private val guardPool: ExecutorService = Executors.newFixedThreadPool(GUARD_THREADS) { task ->
        Thread(task, "zap-guard").apply { isDaemon = true }
    }

    /**
     * Knocks on the addresses of ONE channel right now and reports their verdicts. The whole-table
     * pass is a button, and cinerama moves its advert between edges inside the hours a verdict stays
     * valid — so a zap cannot wait for it. Sources are probed in parallel; the callback fires on the
     * main thread once every address of the row has answered.
     */
    fun verifyNow(sources: List<Source>, onDone: (Map<String, State>) -> Unit) {
        val todo = ArrayList<Source>()
        val seen = HashSet<String>()
        for (source in sources) {
            if (source.isResolver || source.url.isEmpty() || !seen.add(source.url)) continue
            todo.add(source)
        }
        if (todo.isEmpty()) return
        val since = wiped
        val found = ConcurrentHashMap<String, State>()
        val left = AtomicInteger(todo.size)
        todo.forEach { source ->
            guardPool.execute {
                val state = if (SourceStamps.isExpired(source.url)) State.Dead else fetch(source)
                put(source.url, state)
                found[source.url] = state
                if (left.decrementAndGet() == 0) {
                    if (since != wiped) return@execute
                    writeCache()
                    val verdict = found.toMap()
                    main.post { onDone(verdict) }
                }
            }
        }
    }

    private fun probe(channel: Channel, pass: Int) {
        var alive = false
        val probes = channel.sources.filter { !it.isResolver && it.url.isNotEmpty() }
        for (source in probes) {
            if (pass != generation) return
            if (SourceStamps.isExpired(source.url)) {
                put(source.url, State.Dead)
                continue
            }
            val state = fetch(source)
            put(source.url, state)
            if (state == State.Alive) {
                alive = true
                break
            }
        }
        if (pass != generation) return
        if (alive) aliveNames.add(channel.name)
        if (done.incrementAndGet() >= total.get()) finish(pass) else if (done.get() % PROGRESS_EVERY == 0) publish()
    }

    /**
     * A 401/403 is not a dead channel: those servers answer only to the box itself, or need headers
     * this probe cannot guess. A timeout is not death either — the box probes while it plays, so a
     * slow CDN looks exactly like a dead one here. Only a real 4xx/5xx answer buries a row.
     *
     * Redirects are walked by hand: `connection.url` keeps reporting the address the probe started
     * from, so a Location header is the only way to see where a playlist really landed.
     */
    private fun fetch(source: Source): State {
        var at = source.url
        var hop = 0
        while (hop++ <= MAX_HOPS) {
            val connection = runCatching { URL(at).openConnection() as HttpURLConnection }
                .getOrNull() ?: return State.Unknown
            try {
                connection.connectTimeout = CONNECT_MS
                connection.readTimeout = READ_MS
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("User-Agent", source.userAgent ?: USER_AGENT)
                source.referrer?.let { connection.setRequestProperty("Referer", it) }
                val code = connection.responseCode
                if (code in 200..299) {
                    if (isSlate(at)) return State.Dead
                    return if (carriesPicture(source, at, connection)) State.Alive else State.Dead
                }
                if (code == 401 || code == 403) return State.Unknown
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location") ?: return State.Unknown
                    at = URL(URL(at), location).toString()
                    continue
                }
                if (code >= 400) return State.Dead
                return State.Unknown
            } catch (error: Exception) {
                return State.Unknown
            } finally {
                runCatching { connection.disconnect() }
            }
        }
        return State.Unknown
    }

    /**
     * cinerama answers a paid channel it no longer licenses with an advert for its own app: the
     * playlist loads, the edge moves, the player is happy — only the address it was fetched from
     * gives it away. Nothing downstream can catch this, so the probe has to.
     */
    private fun isSlate(landedOn: String) = landedOn.contains("/blocked/")

    /**
     * A 200 on the playlist proves almost nothing: edges have been seen listing segments they no
     * longer serve, and then the picture stalls on the first frame with no error to rotate off. So
     * an address from inside the list gets knocked on too — HEAD only, nothing is downloaded on top
     * of what the player is already pulling.
     *
     * The newest entries of a live list are not written yet and cinerama answers them with a 404 for
     * a second, so the tail of the window is not a fair sample: an older address is asked first, and
     * an edge is only buried when a second one agrees. A server that will not answer HEAD at all (or
     * answers it the way 1tv and the others answer uninvited traffic) keeps its verdict.
     */
    private fun carriesPicture(source: Source, at: String, connection: HttpURLConnection): Boolean {
        val body = runCatching {
            val reader = connection.inputStream.bufferedReader()
            val buffer = CharArray(BODY_CHARS)
            var filled = 0
            while (filled < buffer.size) {
                val read = reader.read(buffer, filled, buffer.size - filled)
                if (read <= 0) break
                filled += read
            }
            String(buffer, 0, filled)
        }.getOrDefault("")
        if (!body.contains("#EXTINF")) return true
        val list = body.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toList()
        if (list.isEmpty()) return true
        val picks = LinkedHashSet<String>().apply {
            list.getOrNull(list.size - 3)?.let { add(it) }
            list.firstOrNull()?.let { add(it) }
        }
        var refused = false
        for (segment in picks) {
            val answer = ask(source, URL(URL(at), segment))
            if (answer == null) return true
            if (answer in 200..399 || answer == 401 || answer == 403 || answer == 405 || answer == 501) {
                return true
            }
            if (answer in 400..599) refused = true
        }
        return !refused
    }

    /** HEAD on one address, or null when the question itself never got through. */
    private fun ask(source: Source, at: URL): Int? = runCatching {
        val check = at.openConnection() as HttpURLConnection
        try {
            check.requestMethod = "HEAD"
            check.connectTimeout = CONNECT_MS
            check.readTimeout = READ_MS
            check.instanceFollowRedirects = false
            check.setRequestProperty("User-Agent", source.userAgent ?: USER_AGENT)
            source.referrer?.let { check.setRequestProperty("Referer", it) }
            check.responseCode
        } finally {
            runCatching { check.disconnect() }
        }
    }.getOrNull()

    private fun put(url: String, state: State) {
        verdicts[url] = state
    }

    private fun finish(pass: Int) {
        if (pass != generation) return
        checkedAt = System.currentTimeMillis()
        writeCache()
        val names = synchronized(aliveNames) { aliveNames.toList() }
        aliveNames.clear()
        PlaybackLog.recordCheck(names.size, names)
        running.set(false)
        publish()
    }

    private fun publish() {
        main.post { onUpdated?.invoke() }
    }

    /** Called from the application's init thread, never from the screen. */
    fun loadCache() {
        val loaded = HashMap<String, State>()
        var time = 0L
        runCatching {
            cacheFile.forEachLine { line ->
                val parts = line.split('\t')
                if (parts.size >= 3) {
                    val stamp = parts[2].toLongOrNull() ?: return@forEachLine
                    if (System.currentTimeMillis() - stamp < KEEP_MS) {
                        loaded[parts[0]] = runCatching { State.valueOf(parts[1]) }
                            .getOrDefault(State.Unknown)
                        time = maxOf(time, stamp)
                    }
                }
            }
        }
        if (loaded.isNotEmpty()) {
            verdicts.putAll(loaded)
            checkedAt = time
        }
    }

    private fun writeCache() {
        val snapshot = verdicts
        runCatching {
            synchronized(disk) {
                val now = System.currentTimeMillis()
                cacheFile.bufferedWriter().use { writer ->
                    snapshot.forEach { (url, state) -> writer.write("$url\t$state\t$now\n") }
                }
            }
        }
    }

    companion object {
        private const val THREADS = 4

        /** Addresses of the channel being tuned are probed at once; a row never has more than a few. */
        private const val GUARD_THREADS = 3
        private const val CONNECT_MS = 7_000
        private const val READ_MS = 12_000

        /** Redirects walked per address before the probe gives up on it. */
        private const val MAX_HOPS = 3

        /** Playlist text read before the probe gives up: live lists are a few hundred bytes. */
        private const val BODY_CHARS = 8_000
        private const val USER_AGENT = "Mozilla/5.0 (Android TV)"

        /** Results younger than this are reused instead of re-downloading the whole table. */
        private const val KEEP_MS = 12 * 60 * 60 * 1000L
        private const val PROGRESS_EVERY = 25

        /** One pass looks at this many channels: a public list of 1300 rows must not hog the box. */
        private const val MAX_PER_PASS = 150
    }
}

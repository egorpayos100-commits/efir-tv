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
import java.util.Locale
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
    private val main = Handler(Looper.getMainLooper())

    // Replaced wholesale by the worker thread; the UI only ever reads a finished map.
    @Volatile
    private var byId: Map<String, List<Programme>> = emptyMap()

    @Volatile
    private var fetching = false

    var onLoaded: (() -> Unit)? = null

    val isEmpty: Boolean get() = byId.isEmpty()

    fun loadCache() {
        if (!cacheFile.exists()) return
        if (age() < 0) return
        val from = System.currentTimeMillis() - KEEP_BEFORE
        val loaded = HashMap<String, ArrayList<Programme>>()
        runCatching {
            cacheFile.forEachLine { line ->
                val parts = line.split('\t')
                if (parts.size >= 4) {
                    val start = parts[1].toLongOrNull() ?: return@forEachLine
                    val end = parts[2].toLongOrNull() ?: return@forEachLine
                    if (end > from) loaded.getOrPut(parts[0]) { ArrayList() }.add(Programme(start, end, parts[3]))
                }
            }
        }
        if (loaded.isNotEmpty()) byId = sorted(loaded)
    }

    /** No-op while the cache is fresh or a fetch is already running, so a timer can call it freely. */
    fun refresh(ids: Set<String>) {
        if (ids.isEmpty() || fetching) return
        if (cacheFile.exists() && age() < MAX_AGE_MS) return
        fetching = true
        Thread {
            val parsed = runCatching { fetch(ids) }.getOrNull()
            fetching = false
            if (parsed != null && parsed.isNotEmpty()) {
                byId = sorted(parsed)
                writeCache(parsed)
                main.post { onLoaded?.invoke() }
            }
        }.apply { isDaemon = true }.start()
    }

    fun slots(epgId: String?, now: Long): Slots {
        val list = epgId?.let { byId[it] } ?: return Slots(null, null, null)
        val index = list.indexOfFirst { it.end > now }
        if (index < 0) return Slots(list.lastOrNull(), null, null)
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

    private fun writeCache(parsed: Map<String, List<Programme>>) {
        runCatching {
            cacheFile.bufferedWriter().use { writer ->
                parsed.forEach { (id, list) ->
                    list.forEach {
                        writer.write("$id\t${it.start}\t${it.end}\t${oneLine(it.title)}\n")
                    }
                }
            }
        }
    }

    private fun oneLine(title: String): String =
        title.replace('\t', ' ').replace('\n', ' ').trim()

    private fun fetch(ids: Set<String>): Map<String, List<Programme>> {
        val now = System.currentTimeMillis()
        val from = now - KEEP_BEFORE
        val to = now + KEEP_AFTER
        var url = URL(FEED)
        repeat(MAX_REDIRECTS) {
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 15_000
                connection.readTimeout = 90_000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("User-Agent", USER_AGENT)
                val code = connection.responseCode
                if (code in 301..308) {
                    val target = connection.getHeaderField("Location") ?: return emptyMap()
                    url = URL(url, target)
                    return@repeat
                }
                if (code != 200) return emptyMap()
                return connection.inputStream.buffered(1 shl 16).use { raw ->
                    GZIPInputStream(raw).use { parse(it, ids, from, to) }
                }
            } finally {
                connection.disconnect()
            }
        }
        return emptyMap()
    }

    private fun parse(input: java.io.InputStream, wanted: Set<String>, from: Long, to: Long): Map<String, List<Programme>> {
        val result = HashMap<String, ArrayList<Programme>>()
        val format = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
        val parser = Xml.newPullParser()
        parser.setInput(input, null)
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
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

    companion object {
        private const val FEED = "http://epg.one/ru2.xml.gz"
        private const val USER_AGENT = "Mozilla/5.0 (Android TV)"
        private const val MAX_AGE_MS = 6 * 60 * 60 * 1000L
        private const val KEEP_BEFORE = 12 * 60 * 60 * 1000L
        private const val KEEP_AFTER = 36 * 60 * 60 * 1000L
        private const val MAX_REDIRECTS = 4
    }
}

/** SimpleDateFormat is not thread-safe; a broken stamp simply means no guide line. */
private fun SimpleDateFormat.parseTime(value: String?): Long? =
    value?.let { runCatching { parse(it)?.time }.getOrNull() }

package plus.efir.tv.data

import android.content.Context
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Where a channel goes when none of its own addresses answer any more: the same channel is looked
 * for in openly published playlists and the player is handed those addresses. Only these fixed list
 * addresses are read — no player page is scraped and no signature is invented — and the lists are
 * maintained by their own authors, so the links in them rotate without anything on the box.
 *
 * This is a rescue at the moment of failure, never a shelf: the rows it finds must not join his
 * table, or a public catalogue buries his own channels the way it did in 0.26.
 */
object ListHealer {

    private const val TAG = "EfirHealer"
    private const val KEEP_MS = 12 * 3_600_000L
    private const val MAX_SOURCES = 6
    private const val MAX_BYTES = 3_000_000
    private const val USER_AGENT = "Mozilla/5.0 (Android TV)"

    private val LISTS = listOf(
        "https://iptv-org.github.io/iptv/countries/ru.m3u",
        "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/ru.m3u",
        "https://iptv-org.github.io/iptv/countries/kz.m3u",
        "https://iptv-org.github.io/iptv/countries/uz.m3u",
        "https://iptv-org.github.io/iptv/countries/by.m3u",
    )

    /**
     * The same channel spelled their way. Their list writes Russian names in Latin letters
     * («Пятница» is not in it at all, «НТВ» is NTV.ru), so names alone would find nothing and the
     * ids are the only stable spelling. Anything missing here falls back to the name match.
     */
    private val IDS = mapOf(
        "russia-hd" to "Russia1.ru@HD",
        "ntv" to "NTV.ru@SD",
        "mir24" to "Mir24.ru@SD",
        "zvezda" to "Zvezda.ru@SD",
        "zvezda-plus" to "ZvezdaPlus.ru@SD",
        "spas" to "Spas.ru@SD",
        "otr" to "OTR.ru@SD",
        "perviy" to "ChannelOne.ru@SD",
        "tnt" to "TNT.ru@SD",
        "sportivny" to "Sportivnyy.ru@SD",
        "eurosport-1" to "Eurosport1.fr@Russia",
        "cinema" to "Cinema.ru@SD",
        "tv1000" to "vijuTV1000.ru@SD",
        "tv1000-action" to "vijuTV1000action.ru@SD",
        "tv1000-russkoe-kino" to "vijuTV1000russkoe.ru@SD",
        "animal-planet" to "AnimalPlanet.ru@SD",
        "discovery" to "DiscoveryChannel.ru@SD",
        "nat-geo" to "NationalGeographic.ru@SD",
        "fashiontv" to "FashionTV.ru@SD",
        "che" to "Che.ru@SD",
    )

    /**
     * Blocking: a few small lists are downloaded and parsed, so call this off the main thread.
     * [mine] is his whole table, needed because a longer channel name of his that also fits a
     * candidate means that candidate is the other channel, not this one.
     */
    fun sources(context: Context, dead: Channel, mine: List<Channel>): List<Source> {
        val taken = HashSet(dead.sources.map { it.url })
        val found = ArrayList<Source>()
        for (url in LISTS) {
            for (row in rows(context, url)) {
                if (!matches(dead, row, mine)) continue
                for (source in row.sources) {
                    if (source.isResolver || source.url.isEmpty() || !taken.add(source.url)) continue
                    if (SourceStamps.isExpired(source.url)) continue
                    found.add(source)
                    if (found.size == MAX_SOURCES) return found
                }
            }
        }
        return found
    }

    private fun matches(dead: Channel, row: Channel, mine: List<Channel>): Boolean {
        IDS[dead.key]?.let { return row.key == it }
        val name = collapse(dead.name)
        if (name.length < 5) return false
        val candidate = collapse(row.name)
        if (!candidate.contains(name)) return false
        return mine.none { other ->
            other.key != dead.key && candidate.contains(collapse(other.name)) &&
                collapse(other.name).length > name.length
        }
    }

    /** Quality marks and the country suffix change row to row, so they are not part of the name. */
    private val NOISE = Regex("""(?i)\b(hd|sd|fhd|uhd|4k|1080p?|720p?|480p?|576p?|russia|rossia|ru)\b""")

    private fun collapse(name: String) =
        name.replace(NOISE, " ").lowercase().filter { it.isLetterOrDigit() }

    /** A list younger than KEEP_MS is read from disk; a failed download falls back to the old copy. */
    private fun rows(context: Context, address: String): List<Channel> {
        val file = File(context.cacheDir, "healer-${address.hashCode()}.m3u")
        val text = if (System.currentTimeMillis() - file.lastModified() < KEEP_MS && file.exists())
            runCatching { file.readText() }.getOrNull() ?: download(address, file)
        else download(address, file) ?: runCatching { file.readText() }.getOrNull()
        return text?.let { runCatching { M3uParser.parse(it) }.getOrNull() }.orEmpty()
    }

    private fun download(address: String, into: File): String? {
        val connection = runCatching { URL(address).openConnection() as HttpURLConnection }
            .getOrNull() ?: return null
        return try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept-Encoding", "gzip")
            if (connection.responseCode != 200) return null
            val stream = if (connection.contentEncoding == "gzip")
                GZIPInputStream(connection.inputStream) else connection.inputStream
            val body = stream.buffered().use { input ->
                val out = java.io.ByteArrayOutputStream(1 shl 16)
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
            if (!body.contains("#EXTM3U")) return null
            runCatching { into.writeText(body) }
            body
        } catch (error: Exception) {
            Log.w(TAG, "список $address не ответил", error)
            null
        } finally {
            runCatching { connection.disconnect() }
        }
    }
}

package plus.efir.tv.data

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.random.Random

/** Outcome of asking a broadcaster's own player for the current address. */
sealed class Resolved {
    /** Headers belong to the address, not to the resolver call: the player needs them on segments. */
    data class Stream(val url: String, val headers: Map<String, String> = emptyMap()) : Resolved()

    /** The broadcaster itself says the picture may not be shown here — no point retrying. */
    data class Blocked(val reason: String) : Resolved()

    /** Network, format or a changed site: worth one re-run, then the usual chain. */
    data class Failed(val reason: String) : Resolved()
}

/**
 * Sources that have no address in the playlist: the current one is taken from the official player
 * of the channel every time the channel is opened. Only the broadcaster's own site is ever asked —
 * no restream pages, no proxies, and nothing that pretends to be another country.
 */
object Resolvers {

    const val ONE_TV = "1tv"
    const val OK_PREFIX = "ok:"

    private const val TAG = "EfirResolver"
    private const val PREFS = "resolvers"
    private const val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Safari/537.36"

    private fun isKnown(id: String) = id == ONE_TV || id.startsWith(OK_PREFIX) || id.startsWith("ok_")

    /** Public-list ids to fall back on when the official player gives nothing. */
    fun backupTvgIds(id: String): List<String> = when {
        id == ONE_TV -> listOf("Channel1Russia.ru", "ChannelOne.ru")
        else -> emptyList()
    }

    /** Blocking — call from a worker thread. Fresh results are cached for a short while. */
    fun resolve(context: Context, id: String, force: Boolean = false): Resolved {
        if (!isKnown(id)) return Resolved.Failed("неизвестный резолвер «$id»")
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!force) {
            val cached = read(prefs, id)
            if (cached != null && cached.second > System.currentTimeMillis()) {
                Log.i(TAG, "$id cache hit → ${cached.first.take(110)}")
                val headers = if (id.startsWith(OK_PREFIX) || id.startsWith("ok_")) OK_STREAM_HEADERS else STREAM_HEADERS
                return Resolved.Stream(cached.first, headers)
            }
        }
        val outcome = runCatching {
            when {
                id == ONE_TV -> oneTv()
                id.startsWith(OK_PREFIX) || id.startsWith("ok_") -> {
                    val videoId = id.substringAfter(':').substringAfter('_').trim()
                    okRu(videoId)
                }
                else -> Resolved.Failed("нет такого резолвера")
            }
        }
            .getOrElse {
                Log.w(TAG, "$id resolve crashed", it)
                Resolved.Failed("резолвер не ответил: ${it.javaClass.simpleName}")
            }
        if (outcome is Resolved.Stream) {
            prefs.edit()
                .putString(key(id), outcome.url)
                .putLong(untilKey(id), System.currentTimeMillis() + TTL_MS)
                .apply()
        }
        Log.i(TAG, "$id → $outcome")
        return outcome
    }

    /** Forget the cached address — the player does this before retrying a dead stream. */
    fun forget(context: Context, id: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(key(id)).apply()
    }

    private fun read(prefs: android.content.SharedPreferences, id: String): Pair<String, Long>? {
        val url = prefs.getString(key(id), null) ?: return null
        return url to prefs.getLong(untilKey(id), 0L)
    }

    private fun key(id: String) = "url_$id"
    private fun untilKey(id: String) = "until_$id"

    /**
     * What the page at https://www.1tv.ru/live does, read off its own player bundle
     * (`static.1tv.ru/eump/configs/1tv_live.js`):
     *
     *  1. `GET https://stream.1tv.ru/api/playlist/1tvch-v1_as_array.json?r=<random>` — the live
     *     table for channel «1tvch». JSON: `country`, `hls[]`, `mpd[]`, `p2p[]`. The `hls` entries
     *     point at the player's own session proxy (`mobdrm.1tv.ru/...?cdn=<cdn>&netprav=<stub>&e=t`)
     *     and answer 403 without the session the browser player keeps in memory, so they are not
     *     usable here. The `mpd` entries are the same picture straight from the CDN, unencrypted.
     *  2. `GET https://stream.1tv.ru/api/restrictions.json?r=<random>` — `{geo:[{geo:"ALL"}]}`, the
     *     rights scope of the programme on air right now; anything but `ALL`/`RU` for our country
     *     means the channel itself has switched the picture off for this region.
     *
     * Only these two endpoints of the broadcaster are contacted, with the same desktop UA, Referer
     * and Origin the player sends.
     */
    private fun oneTv(): Resolved {
        val source = body("$PLAYLIST_URL${randomSeed()}")
            ?: return Resolved.Failed("плейлист 1TV не отвечает")
        val table = JSONObject(source)
        val country = table.optString("country")
        val mpd = table.optJSONArray("mpd")
        val hls = table.optJSONArray("hls")
        val manifests = (0 until (mpd?.length() ?: 0)).mapNotNull { mpd?.optString(it) }
            .filter { it.isNotBlank() }
        val hlsUrls = (0 until (hls?.length() ?: 0)).mapNotNull { hls?.optString(it) }
            .filter { it.isNotBlank() }

        val stub = hlsUrls.any { it.contains("no_src") } && manifests.isEmpty()
        if (stub) return Resolved.Blocked("1TV отдаёт заглушку: в эфире материал без права на показ в сети")
        if (manifests.isEmpty() && hlsUrls.isEmpty()) {
            return Resolved.Blocked("1TV не дал ни одной ссылки (страна ответа: $country)")
        }

        val geo = body("$RESTRICTIONS_URL${randomSeed()}")?.let { runCatching { JSONObject(it) }.getOrNull() }
        val scope = geo?.optJSONArray("geo")?.optJSONObject(0)?.optString("geo")?.uppercase()
        if (!scope.isNullOrBlank() && scope != "ALL" && !scope.contains(country.uppercase())) {
            return Resolved.Blocked("1TV закрыл этот эфир для страны $country (право только: $scope)")
        }

        // The DASH table is the one verified to play without a player session. The broadcaster hands
        // out several CDNs and not all of them always answer, so the player gets the first that does:
        // a dead edge then costs a few hundred milliseconds, not a black screen.
        val live = manifests.firstOrNull { reachable(it) }
        if (live != null) return Resolved.Stream(live, STREAM_HEADERS)
        val spare = hlsUrls.firstOrNull { !it.contains("no_src") }
        if (spare != null && reachable(spare)) return Resolved.Stream(spare, STREAM_HEADERS)
        return Resolved.Failed("1TV выдал ссылки, которые не отвечают (страна ответа: $country)")
    }

    private fun body(address: String): String? = body(address, 10_000, 15_000)

    /** A manifest the box will actually load is worth the few hundred ms it takes to check. */
    private fun reachable(address: String): Boolean = body(address, 4_000, 6_000)?.isNotBlank() == true

    private fun body(
        address: String,
        connectMs: Int,
        readMs: Int,
        headers: Map<String, String> = API_HEADERS,
    ): String? = runCatching {
        val connection = URL(address).openConnection() as HttpURLConnection
        connection.connectTimeout = connectMs
        connection.readTimeout = readMs
        connection.instanceFollowRedirects = true
        headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
        connection.setRequestProperty("User-Agent", DESKTOP_USER_AGENT)
        if (connection.responseCode != 200) return null
        connection.inputStream.buffered().use { it.readBytes().toString(Charsets.UTF_8) }
            .takeIf { it.length < 200_000 }
    }.getOrNull()

    private val OK_STREAM_HEADERS = mapOf(
        "User-Agent" to DESKTOP_USER_AGENT,
        "Referer" to "https://ok.ru/",
    )

    private fun okRu(videoId: String): Resolved = runCatching {
        if (videoId.isBlank()) return Resolved.Failed("OK.ru video id пустой")
        val address = "https://ok.ru/video/$videoId"
        val connection = URL(address).openConnection() as HttpURLConnection
        connection.connectTimeout = 8_000
        connection.readTimeout = 12_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", DESKTOP_USER_AGENT)
        if (connection.responseCode != 200) {
            return Resolved.Failed("OK.ru вернул HTTP ${connection.responseCode}")
        }
        val regex = Regex("""hlsMasterPlaylistUrl(?:&quot;|\\"):(?:&quot;|\\")([^"&]+(?:\?[^"&]*)?)(?:&quot;|\\")""")
        var streamUrl: String? = null
        connection.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                if ("hlsMasterPlaylistUrl" in line) {
                    val m = regex.find(line)
                    if (m != null) {
                        streamUrl = m.groupValues[1]
                            .replace("\\u0026", "&")
                            .replace("&amp;", "&")
                            .replace("\\/", "/")
                        break
                    }
                }
            }
        }
        val found = streamUrl
        if (found != null && found.startsWith("http")) {
            Resolved.Stream(found, OK_STREAM_HEADERS)
        } else {
            Resolved.Failed("hlsMasterPlaylistUrl не найден на странице OK.ru $videoId")
        }
    }.getOrElse {
        Log.w(TAG, "okRu resolve failed", it)
        Resolved.Failed("Ошибка запроса OK.ru: ${it.message}")
    }

    /** What the browser player puts on its own API calls. */
    private val API_HEADERS = mapOf(
        "Referer" to "https://www.1tv.ru/live",
        "Origin" to "https://www.1tv.ru",
    )

    /** The CDN serves the picture without any of this, but the page's referer keeps it polite. */
    private val STREAM_HEADERS = mapOf("Referer" to "https://www.1tv.ru/live")

    private const val PLAYLIST_URL =
        "https://stream.1tv.ru/api/playlist/1tvch-v1_as_array.json?r="
    private const val RESTRICTIONS_URL = "https://stream.1tv.ru/api/restrictions.json?r="

    /** Long enough to survive a channel switch back and forth, short enough to dodge a CDN move. */
    private const val TTL_MS = 10 * 60_000L

    private fun randomSeed() = Random.nextInt(100000, 999999)
}

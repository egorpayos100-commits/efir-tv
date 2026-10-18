package plus.efir.tv.data

import android.content.Context
import plus.efir.tv.data.Channel

class ChannelRepository(private val context: Context) {

    private var cached: List<Channel>? = null
    private var cachedFilms: List<Channel>? = null

    fun channels(): List<Channel> = cached ?: load().also { cached = it }

    /** Own library: the same playlist format, but nothing zaps or gets a programme guide. */
    fun films(): List<Channel> = cachedFilms ?: loadFilms().also { cachedFilms = it }

    fun byKey(key: String): Channel? = channels().firstOrNull { it.key == key }

    fun byGroup(): List<Pair<String, List<Channel>>> =
        channels().groupBy { it.group }.toList().sortedBy { it.first }

    fun filmByGroup(): List<Pair<String, List<Channel>>> =
        films().groupBy { it.group }.toList().sortedBy { it.first }

    private fun readAsset(vararg names: String): String? =
        names.firstNotNullOfOrNull { name ->
            runCatching { context.assets.open(name).bufferedReader().use { it.readText() } }.getOrNull()
        }

    private fun load(): List<Channel> {
        val text = readAsset(PREFERS, FALLBACK) ?: return emptyList()
        // Playlist order is the channel numbering the viewer expects, so numbers follow it.
        return M3uParser.parse(text).mapIndexed { index, channel -> channel.copy(number = index + 1) }
    }

    private fun loadFilms(): List<Channel> {
        val text = readAsset(FILMS, FILMS_FALLBACK) ?: return emptyList()
        return M3uParser.parse(text, M3uParser.FILM_GROUP).mapIndexed { index, channel ->
            // Prefixed so a film named like a channel cannot steal its favourite star.
            channel.copy(key = "film:${channel.key}", number = index + 1, kind = Kind.Film)
        }
    }

    fun reload() {
        cached = null
        cachedFilms = null
    }

    companion object {
        private const val PREFERS = "channels.m3u"
        private const val FALLBACK = "channels.example.m3u"
        private const val FILMS = "films.m3u"
        private const val FILMS_FALLBACK = "films.example.m3u"
    }
}

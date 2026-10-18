package plus.efir.tv.data

import android.content.Context
import plus.efir.tv.data.Channel

class ChannelRepository(private val context: Context) {

    private var cached: List<Channel>? = null

    fun channels(): List<Channel> = cached ?: load().also { cached = it }

    fun byKey(key: String): Channel? = channels().firstOrNull { it.key == key }

    fun byGroup(): List<Pair<String, List<Channel>>> =
        channels().groupBy { it.group }.toList().sortedBy { it.first }

    private fun load(): List<Channel> {
        val text = listOf(PREFERS, FALLBACK).firstNotNullOfOrNull { name ->
            runCatching { context.assets.open(name).bufferedReader().use { it.readText() } }.getOrNull()
        } ?: return emptyList()
        return M3uParser.parse(text)
    }

    fun reload() { cached = null }

    companion object {
        private const val PREFERS = "channels.m3u"
        private const val FALLBACK = "channels.example.m3u"
    }
}

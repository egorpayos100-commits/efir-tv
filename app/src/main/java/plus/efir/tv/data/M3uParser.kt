package plus.efir.tv.data

private val ATTR = Regex("""([\w-]+)="([^"]*)"""")

internal class Draft(defaultGroup: String) {
    var id: String? = null
    var name: String = ""
    var group: String = defaultGroup
    var logo: String? = null
    var epgId: String? = null
    var userAgent: String? = null
    var referrer: String? = null
}

object M3uParser {

    /** The shelf a row lands on when its `group-title` is missing. */
    const val LIVE_GROUP = "Каналы"
    const val FILM_GROUP = "Фильмы"

    private const val PICON = "http://epg.one/img2/"

    fun parse(text: String, defaultGroup: String = LIVE_GROUP): List<Channel> {
        val merged = LinkedHashMap<String, Channel>()
        var draft: Draft? = null

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue

            when {
            line.startsWith("#EXTM3U") -> Unit
            line.startsWith("#EXTINF") -> draft = readExtInf(line, defaultGroup)
            line.startsWith("#EXTVLCOPT") -> readVlcOpt(line)?.let { (k, v) ->
                val d = draft ?: Draft(defaultGroup).also { draft = it }
                when (k) {
                    "http-user-agent" -> d.userAgent = v
                    "http-referrer", "http-referer" -> d.referrer = v
                }
            }
            line.startsWith("#") -> Unit
            else -> {
                val d = draft ?: Draft(defaultGroup).also { draft = it }
                if (d.name.isBlank()) d.name = fallbackName(line)
                val key = (d.id ?: d.name).trim().ifBlank { line }
                val source = Source(line, d.userAgent, d.referrer)
                val existing = merged[key]
                merged[key] = if (existing == null) {
                    Channel(key, d.name, d.group, d.logo, listOf(source), epgId = d.epgId)
                } else if (existing.sources.any { it.url == source.url }) {
                    existing
                } else {
                    existing.copy(sources = existing.sources + source)
                }
            }
            }
        }
        return merged.values.toList()
    }

    private fun readExtInf(line: String, defaultGroup: String): Draft {
        val d = Draft(defaultGroup)
        val comma = line.indexOf(',')
        if (comma >= 0) d.name = line.substring(comma + 1).trim()
        ATTR.findAll(line.substringBeforeLast(',')).forEach { m ->
            when (m.groupValues[1]) {
                "tvg-id", "channel-id", "tvg-name" -> if (d.id == null) d.id = m.groupValues[2]
                "tvg-logo", "logo" -> d.logo = m.groupValues[2]
                "epg-id" -> d.epgId = m.groupValues[2]
                "group-title" -> d.group = m.groupValues[2].ifBlank { d.group }
            }
        }
        if (d.name.isBlank()) d.name = d.id.orEmpty()
        if (d.logo.isNullOrBlank()) d.logo = d.epgId?.let { "$PICON$it.png" }
        return d
    }

    private fun readVlcOpt(line: String): Pair<String, String>? {
        val body = line.substringAfter("#EXTVLCOPT:", "").trim()
        val eq = body.indexOf('=')
        if (eq <= 0) return null
        return body.substring(0, eq).trim().lowercase() to body.substring(eq + 1).trim()
    }

    private fun fallbackName(url: String): String =
        url.substringBefore('?').trimEnd('/').substringAfterLast('/').ifBlank { url }
}

package plus.efir.tv.data

private val ATTR = Regex("""([\w-]+)="([^"]*)"""")

internal class Draft {
    var id: String? = null
    var name: String = ""
    var group: String = "Каналы"
    var logo: String? = null
    var userAgent: String? = null
    var referrer: String? = null
}

object M3uParser {

    fun parse(text: String): List<Channel> {
        val merged = LinkedHashMap<String, Channel>()
        var draft: Draft? = null

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue

            when {
            line.startsWith("#EXTM3U") -> Unit
            line.startsWith("#EXTINF") -> draft = readExtInf(line)
            line.startsWith("#EXTVLCOPT") -> readVlcOpt(line)?.let { (k, v) ->
                val d = draft ?: Draft().also { draft = it }
                when (k) {
                    "http-user-agent" -> d.userAgent = v
                    "http-referrer", "http-referer" -> d.referrer = v
                }
            }
            line.startsWith("#") -> Unit
            else -> {
                val d = draft ?: Draft().also { draft = it }
                if (d.name.isBlank()) d.name = fallbackName(line)
                val key = (d.id ?: d.name).trim().ifBlank { line }
                val source = Source(line, d.userAgent, d.referrer)
                val existing = merged[key]
                merged[key] = if (existing == null) {
                    Channel(key, d.name, d.group, d.logo, listOf(source))
                } else if (existing.sources.any { it.url == source.url }) {
                    existing
                } else {
                    existing.copy(sources = existing.sources + source)
                }
                draft = null
            }
            }
        }
        return merged.values.toList()
    }

    private fun readExtInf(line: String): Draft {
        val d = Draft()
        val comma = line.indexOf(',')
        if (comma >= 0) d.name = line.substring(comma + 1).trim()
        ATTR.findAll(line.substringBeforeLast(',')).forEach { m ->
            when (m.groupValues[1]) {
                "tvg-id", "channel-id", "tvg-name" -> if (d.id == null) d.id = m.groupValues[2]
                "tvg-logo", "logo" -> d.logo = m.groupValues[2]
                "group-title" -> d.group = m.groupValues[2].ifBlank { d.group }
            }
        }
        if (d.name.isBlank()) d.name = d.id.orEmpty()
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

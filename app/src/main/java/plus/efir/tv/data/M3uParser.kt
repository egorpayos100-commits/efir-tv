package plus.efir.tv.data

import org.json.JSONObject

private val ATTR = Regex("""([\w-]+)="([^"]*)"""")

/** Everything read from the directives that sit above one address. */
internal class Draft(defaultGroup: String) {
    var id: String? = null
    var name: String = ""
    var group: String = defaultGroup
    var logo: String? = null
    var epgId: String? = null
    /** Resolver id from `resolver="1tv"`: the address is fetched at play time, not stored here. */
    var resolver: String? = null
    /** True once an address line under this directive was consumed; resolver rows have none. */
    var used = false
    /** Set only by `#EXTINF`: it is what makes following address lines backups of one channel. */
    var authored = false
    /** Header name → value, exactly as the playlist asked for it. */
    val headers = LinkedHashMap<String, String>()

    /** Cinema rows only: what the film page is built from when the poster is opened. */
    var year: Int? = null
    var rating: Double? = null
    var imdb: Double? = null
    var genre: String? = null
    var description: String? = null
    var backdrop: String? = null
    var trailer: String? = null
    var featured = false
    var quality: String? = null
    /** Loudness correction for this row, decibels: the player makes every row equally loud. */
    var gain: Float = 0f

    /** A second directive line must add to what is already known, never wipe it out. */
    fun mergeFrom(other: Draft) {
        if (headers.isEmpty()) headers.putAll(other.headers)
        if (resolver == null) resolver = other.resolver
    }
}

object M3uParser {

    /** The shelf a row lands on when its `group-title` is missing. */
    const val LIVE_GROUP = "Каналы"
    const val FILM_GROUP = "Фильмы"

    private const val PICON = "http://epg.one/img2/"
    private const val MAX_SOURCES = 6

    fun parse(text: String, defaultGroup: String = LIVE_GROUP): List<Channel> {
        val merged = LinkedHashMap<String, Channel>()
        var draft: Draft? = null

        /** A row that only names a resolver has no address line, so it must not be lost. */
        fun keep(entry: Draft, url: String) {
            if (entry.name.isBlank()) entry.name = fallbackName(url)
            val key = (entry.id ?: entry.name).trim().ifBlank { url }
            val known = merged[key]
            val source = Source(url, entry.headers, entry.resolver)
            when {
                known == null -> merged[key] = Channel(
                    key, entry.name, renamed(entry.group), entry.logo, listOf(source),
                    epgId = entry.epgId,
                    year = entry.year,
                    rating = entry.rating,
                    imdbRating = entry.imdb,
                    genre = entry.genre,
                    description = entry.description,
                    backdropUrl = entry.backdrop,
                    trailerUrl = entry.trailer,
                    featured = entry.featured,
                    quality = entry.quality,
                    gainDb = entry.gain,
                )
                known.sources.any { it.url == url && url.isNotEmpty() } ||
                    known.sources.size >= MAX_SOURCES -> Unit
                else -> merged[key] = known.copy(sources = known.sources + source)
            }
        }

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue

            when {
                line.startsWith("#EXTM3U") -> Unit
                line.startsWith("#EXTINF") -> {
                    val previous = draft
                    if (previous != null && previous.resolver != null && !previous.used) {
                        // A resolver row ends here: its headers and resolver id must not leak
                        // down into the channel below it.
                        keep(previous, "")
                        draft = null
                    }
                    val next = readExtInf(line, defaultGroup)
                    next.authored = true
                    // Only a header block that still has no entry of its own may be inherited.
                    draft?.takeIf { !it.used }?.let { next.mergeFrom(it) }
                    draft = next
                    if (next.resolver != null) {
                        keep(next, "")
                        next.resolver = null
                    }
                }
                // A bare group line (VLC style) applies to the entry below it.
                line.startsWith("#EXTGRP") -> draft = hold(draft, defaultGroup).also {
                    it.group = valueAfter(line).ifBlank { it.group }
                }
                line.startsWith("#EXTVLCOPT") -> readVlcOpt(line)?.let { (k, v) ->
                    val d = hold(draft, defaultGroup)
                    when (k) {
                        "http-user-agent" -> d.headers["User-Agent"] = v
                        "http-referrer", "http-referer" -> d.headers["Referer"] = v
                        "http-header-lines" -> v.split(";").forEach { pair ->
                            val eq = pair.indexOf('=')
                            if (eq > 0) d.headers[pair.substring(0, eq).trim()] =
                                pair.substring(eq + 1).trim()
                        }
                    }
                    draft = d
                }
                line.startsWith("#EXTHTTP") -> readExtHttp(line).forEach { (k, v) ->
                    val d = hold(draft, defaultGroup)
                    d.headers[normalizeHeader(k)] = v
                    draft = d
                }
                line.startsWith("#") -> Unit
                else -> {
                    val url = cleanUrl(line)
                    if (url == null) continue
                    // Several addresses under one `#EXTINF` are backups of the same channel, so an
                    // entry opened by `#EXTINF` stays open: headers written between two of them
                    // belong to the second. A bare list of URLs has no entry to stay inside.
                    val open = draft?.takeIf { it.authored }
                    val d = open ?: Draft(defaultGroup)
                    if (d.name.isBlank() && d.id == null) d.name = fallbackName(url)
                    d.used = true
                    keep(d, url)
                    if (open == null) draft = null
                }
            }
        }

        val trailing = draft
        if (trailing?.resolver != null && !trailing.used) keep(trailing, "")

        // A duplicate address borrowed by a differently spelled name is the same channel twice.
        val owners = HashMap<String, String>()
        val kept = ArrayList<Channel>(merged.size)
        for (channel in merged.values) {
            val fresh = channel.sources.filter {
                // Resolver rows carry no address of their own, so they can never be a duplicate.
                it.isResolver || owners.put(it.url, channel.key) == null
            }
            if (fresh.isEmpty()) continue
            kept += if (fresh.size == channel.sources.size) channel
            else channel.copy(sources = fresh)
        }
        return kept.filter { it.sources.isNotEmpty() }
    }

    /**
     * His shelves keep their own names, so a remote playlist written before the rename still lands
     * in the folder he now calls «Познавательные».
     */
    private fun renamed(group: String): String =
        if (group.trim().lowercase() == "развлечение") "Познавательные" else group

    private fun readExtInf(line: String, defaultGroup: String): Draft {
        val d = Draft(defaultGroup)
        val comma = titleComma(line)
        // Some lists write "#EXTINF:-1 Name" with no comma before the title at all.
        if (comma >= 0) d.name = line.substring(comma + 1).trim()
        val attributePart = if (comma >= 0) line.substring(0, comma) else line
        ATTR.findAll(attributePart).forEach { m ->
            when (m.groupValues[1]) {
                "tvg-id", "channel-id" -> if (d.id == null) d.id = m.groupValues[2]
                "tvg-name" -> if (d.name.isBlank()) d.name = m.groupValues[2]
                "tvg-logo", "logo" -> d.logo = m.groupValues[2]
                "epg-id" -> d.epgId = m.groupValues[2]
                "group-title" -> d.group = m.groupValues[2].ifBlank { d.group }
                "resolver" -> d.resolver = m.groupValues[2].trim().ifBlank { null }
                "tvg-year", "year" -> d.year = m.groupValues[2].toIntOrNull()
                "tvg-rating", "rating" -> d.rating = m.groupValues[2].toDoubleOrNull()
                "tvg-imdb", "imdb" -> d.imdb = m.groupValues[2].toDoubleOrNull()
                "tvg-genre", "genre" -> d.genre = m.groupValues[2].ifBlank { null }
                "tvg-description", "description" -> d.description = m.groupValues[2].ifBlank { null }
                "tvg-backdrop", "backdrop" -> d.backdrop = m.groupValues[2].ifBlank { null }
                "tvg-trailer", "trailer" -> d.trailer = m.groupValues[2].ifBlank { null }
                "tvg-featured", "featured" -> d.featured =
                    m.groupValues[2].equals("1", true) || m.groupValues[2].equals("true", true)
                "tvg-quality", "quality" -> d.quality =
                    m.groupValues[2].trim().uppercase().ifBlank { null }
                "tvg-gain", "gain" -> d.gain = m.groupValues[2].trim().replace(',', '.')
                    .toFloatOrNull() ?: 0f
                // iptv-org writes the same headers VLC uses as plain attributes of `#EXTINF`.
                "http-user-agent" -> d.headers["User-Agent"] = m.groupValues[2]
                "http-referrer", "http-referer" -> d.headers["Referer"] = m.groupValues[2]
            }
        }
        if (d.name.isBlank()) d.name = d.id.orEmpty().substringBefore('.')
        if (d.logo.isNullOrBlank()) d.logo = d.epgId?.let { "$PICON$it.png" }
        return d
    }

    /**
     * The comma that ends the attributes, not the first one in the line: a genre or a synopsis is
     * full of commas inside its quotes, and cutting there turned the film's name into half a genre.
     */
    private fun titleComma(line: String): Int {
        var quoted = false
        for (i in line.indices) {
            val c = line[i]
            if (c == '"') quoted = !quoted
            else if (c == ',' && !quoted) return i
        }
        return -1
    }

    private fun hold(draft: Draft?, defaultGroup: String): Draft = draft ?: Draft(defaultGroup)

    private fun valueAfter(line: String): String =
        line.substringAfter(':', "").trim()

    private fun readVlcOpt(line: String): Pair<String, String>? {
        val body = valueAfter(line)
        val eq = body.indexOf('=')
        if (eq <= 0) return null
        return body.substring(0, eq).trim().lowercase() to body.substring(eq + 1).trim()
    }

    /** `#EXTHTTP:{"user-agent":"…","referer":"…"}` — the header block IPTV lists prefer. */
    private fun readExtHttp(line: String): Map<String, String> {
        val body = valueAfter(line)
        if (!body.startsWith("{")) return emptyMap()
        return runCatching {
            val json = JSONObject(body)
            json.keys().asSequence().associateWith { json.optString(it) }
                .filterValues { it.isNotBlank() }
        }.getOrDefault(emptyMap())
    }

    /** Different lists spell the same header four ways; ExoPlayer needs the wire names. */
    private fun normalizeHeader(key: String): String = when (key.lowercase().replace('_', '-')) {
        "user-agent", "ua" -> "User-Agent"
        "referer", "referrer" -> "Referer"
        else -> key.split('-').joinToString("-") { it.replaceFirstChar(Char::uppercaseChar) }
    }

    /** A whole `<iframe>` block pasted as an address line still names exactly one video. */
    private val EMBED_IN_LINE = Regex(
        """https?://[^\s"'<>]*video_ext\.php[^\s"'<>]*""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Playlists typed by hand are full of spaces, quotes and stray characters inside the address.
     * Percent-encoding the spaces is what makes such a row playable instead of a 400.
     */
    internal fun cleanUrl(raw: String): String? {
        var url = (EMBED_IN_LINE.find(raw)?.value ?: raw).trim().trim('"', '\'', '`', '<', '>')
        if (url.isEmpty() || url.startsWith("#")) return null
        url = url.replace(" ", "%20")
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true) &&
            !url.startsWith("rtmp", true)
        ) return null
        return url
    }

    private fun fallbackName(url: String): String =
        url.substringBefore('?').trimEnd('/').substringAfterLast('/').ifBlank { url }
}

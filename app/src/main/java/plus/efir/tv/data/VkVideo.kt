package plus.efir.tv.data

import android.net.Uri

/**
 * A VK video link names a page, not a stream: the media behind it is signed and short-lived.
 * Such a row is handed to the WebView player instead of the ExoPlayer.
 */
object VkVideo {

    /** An address that already names the embed player, as copied from an iframe. */
    private val EMBED = Regex(
        """https?://(?:[\w-]+\.)*(?:vk\.com|vk\.ru|vkvideo\.ru)/video_ext\.php\?[^\s"'<>]+""",
        RegexOption.IGNORE_CASE
    )

    /** A page address: `video-<owner>_<id>`, the owner is negative for a community. */
    private val PAGE = Regex(
        """https?://(?:[\w-]+\.)*(?:vk\.com|vk\.ru|vkvideo\.ru)/video(-?\d+)_(\d+)(?:\?([^\s"'<>]+))?""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Extracts "oid_id" from any VK video address, e.g. "-220018529_456240212".
     */
    fun videoId(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        EMBED.find(text)?.let { match ->
            val uri = Uri.parse(match.value)
            val oid = uri.getQueryParameter("oid")
            val id = uri.getQueryParameter("id")
            if (!oid.isNullOrBlank() && !id.isNullOrBlank()) {
                val cleanOid = if (oid.startsWith("-")) oid else "-$oid"
                return "${cleanOid}_$id"
            }
        }
        val page = PAGE.find(text) ?: return null
        val oid = page.groupValues[1]
        val cleanOid = if (oid.startsWith("-")) oid else "-$oid"
        return "${cleanOid}_${page.groupValues[2]}"
    }

    /**
     * The embed address for a VK page, or null when the row is not one.
     * The `hash` is the access key of an unlisted video: without it VK answers «invalid user».
     */
    fun embedUrl(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty()) return null

        EMBED.find(text)?.let { match ->
            val uri = Uri.parse(match.value)
            val oid = uri.getQueryParameter("oid")
            val id = uri.getQueryParameter("id")
            if (!oid.isNullOrBlank() && !id.isNullOrBlank()) {
                return build(uri.scheme ?: "https", uri.authority ?: "vk.com", oid, id,
                    uri.getQueryParameter("hash"))
            }
        }

        val page = PAGE.find(text) ?: return null
        val hash = page.groupValues[3].split('&')
            .firstOrNull { it.startsWith("hash=") }?.substringAfter('=')
        return build("https", "vk.com", page.groupValues[1], page.groupValues[2], hash)
    }

    private fun build(scheme: String, authority: String, oid: String, id: String, hash: String?): String {
        val builder = Uri.Builder()
            .scheme(scheme)
            .authority(authority)
            .path("/video_ext.php")
            .appendQueryParameter("oid", oid)
            .appendQueryParameter("id", id)
            .appendQueryParameter("autoplay", "1")
            .appendQueryParameter("js_api", "1")
        if (!hash.isNullOrBlank()) builder.appendQueryParameter("hash", hash)
        return builder.build().toString()
    }
}

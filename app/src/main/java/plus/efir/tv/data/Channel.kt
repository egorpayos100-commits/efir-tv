package plus.efir.tv.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.LruCache

/**
 * One playable address plus the request headers the playlist demands for it.
 * `#EXTVLCOPT` and `#EXTHTTP` rows carry them; without them many IPTV servers answer 403.
 *
 * A row with `resolver` set has no address of its own: [plus.efir.tv.data.Resolvers] asks the
 * broadcaster's own player for the current one every time the channel is opened.
 */
data class Source(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val resolver: String? = null,
) {
    val isResolver: Boolean get() = resolver != null
    val userAgent: String? get() = headers["User-Agent"]
    val referrer: String? get() = headers["Referer"]
}

enum class Kind { Live, Film }

data class Channel(
    val key: String,
    val name: String,
    val group: String,
    val logoUrl: String?,
    val sources: List<Source>,
    val number: Int = 0,
    /** Channel id inside the XMLTV guide, unrelated to `key`. */
    val epgId: String? = null,
    /** Live rows zap on focus and get an EPG strip; films open only on ОК and get a poster tile. */
    val kind: Kind = Kind.Live,
    /** A row that runs a command instead of playing something: it has no library file to miss. */
    val isAction: Boolean = false,
    /** Film rows only: what the poster cell writes under the name and in its corner. */
    val year: Int? = null,
    val rating: Double? = null,
    /** Film card only: everything the cinema page shows once the poster is opened. */
    val imdbRating: Double? = null,
    val genre: String? = null,
    val description: String? = null,
    val backdropUrl: String? = null,
    val trailerUrl: String? = null,
    /** The row hung above the shelf as «Новинка» while the playlist keeps it marked. */
    val featured: Boolean = false,
    /**
     * Film rows only: `TS`, `CAM` and the like, set while the shelf holds a copy that is still a
     * camera capture. The harvester drops the mark the moment it swaps in an HD address.
     */
    val quality: String? = null,
    /**
     * How far this row's own loudness sits from the common level, in decibels (`tvg-gain`).
     * The player applies it, so a quiet film and a loud channel sound equally loud.
     */
    val gainDb: Float = 0f,
) {
    /** Placeholder artwork until the channel's own logo arrives. */
    fun swatch(width: Int, height: Int): Bitmap = Tiles.get(key, monogram, width, height)

    /** «Одиссея (2026)»: the year is a caption, not part of the name written on the poster. */
    val displayName: String
        get() = if (kind == Kind.Film) name.replace(YEAR_TAIL, "").trim() else name

    val releaseYear: Int?
        get() = year ?: YEAR_TAIL.find(name)?.groupValues?.get(1)?.toIntOrNull()

    companion object {
        private val YEAR_TAIL = Regex("""\s*\((\d{4})\)\s*$""")
    }

    private val monogram: String by lazy {
        val words = name.split(' ', '-').filter { it.isNotBlank() }
        when {
            words.size >= 2 -> words.take(2).map { it.first() }.joinToString("").uppercase()
            words.isEmpty() -> "?"
            else -> words.first().take(4).uppercase()
        }
    }
}

/**
 * Monogram tiles live in one small shared cache. Keeping one per channel looks cheaper in code but
 * holds a bitmap for every row ever scrolled past: on the box that is what filled the heap.
 */
private object Tiles {

    /**
     * A poster plate is 160x240 now, and the wall shows a dozen of them at once: at two megabytes
     * the cache threw away the cards he had just scrolled past, and each way back redrew the
     * gradient on the UI thread.
     */
    private const val CACHE_BYTES = 6 * 1024 * 1024

    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun get(key: String, monogram: String, width: Int, height: Int): Bitmap {
        val tileKey = "$key|$width×$height"
        cache.get(tileKey)?.takeIf { !it.isRecycled }?.let { return it }
        return draw(key, monogram, width, height).also { cache.put(tileKey, it) }
    }

    private fun draw(key: String, monogram: String, width: Int, height: Int): Bitmap {
        val hue = (Math.abs(key.hashCode()) % 360).toFloat()
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val radius = minOf(width, height) * 0.16f
        val rect = RectF(0f, 0f, width.toFloat(), height.toFloat())

        val background = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, 0f, height.toFloat(),
                Color.HSVToColor(floatArrayOf(hue, 0.62f, 0.40f)),
                Color.HSVToColor(floatArrayOf(hue, 0.72f, 0.16f)),
                Shader.TileMode.CLAMP
            )
        }
        c.drawRoundRect(rect, radius, radius, background)

        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = height * 0.035f
            color = Color.HSVToColor(floatArrayOf(hue, 0.45f, 0.85f))
            alpha = 120
        }
        val inset = border.strokeWidth / 2f
        c.drawRoundRect(
            RectF(inset, inset, width - inset, height - inset), radius, radius, border
        )

        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.HSVToColor(floatArrayOf(hue, 0.18f, 1f))
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = height * 0.44f
            var size = textSize
            while (measureText(monogram) > width * 0.76f && size > height * 0.12f) {
                size *= 0.92f
                textSize = size
            }
        }
        val y = height / 2f - (label.descent() + label.ascent()) / 2f
        c.drawText(monogram, width / 2f, y, label)
        return bmp
    }
}

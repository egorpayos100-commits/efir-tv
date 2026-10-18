package plus.efir.tv.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

data class Source(
    val url: String,
    val userAgent: String? = null,
    val referrer: String? = null,
)

data class Channel(
    val key: String,
    val name: String,
    val group: String,
    val logoUrl: String?,
    val sources: List<Source>,
    val number: Int = 0,
) {
    val isLiveSource: Boolean get() = sources.isNotEmpty()

    // Rows rebind on every scroll; allocating a tile each time is visible on the box.
    private var tileCache: Bitmap? = null
    private var tileKey = 0L

    fun swatch(width: Int, height: Int): Bitmap {
        val signature = width.toLong() shl 32 or height.toLong()
        tileCache?.let { if (tileKey == signature && !it.isRecycled) return it }
        val hue = (Math.abs(key.hashCode()) % 360).toFloat()
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.HSVToColor(floatArrayOf(hue, 0.55f, 0.32f)))
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.HSVToColor(floatArrayOf(hue, 0.65f, 0.95f))
            textSize = height * 0.5f
            textAlign = Paint.Align.CENTER
        }
        val initial = name.trim().firstOrNull()?.toString() ?: "?"
        val y = height / 2f - (p.descent() + p.ascent()) / 2f
        c.drawText(initial, width / 2f, y, p)
        tileCache = bmp
        tileKey = signature
        return bmp
    }
}

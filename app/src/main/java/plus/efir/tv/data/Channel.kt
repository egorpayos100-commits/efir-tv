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
) {
    val isLiveSource: Boolean get() = sources.isNotEmpty()

    fun swatch(width: Int, height: Int): Bitmap {
        val hue = (Math.abs(key.hashCode()) % 360).toFloat()
        val bg = Color.HSVToColor(floatArrayOf(hue, 0.55f, 0.32f))
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(bg)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.HSVToColor(floatArrayOf(hue, 0.65f, 0.95f))
            textSize = height * 0.34f
            textAlign = Paint.Align.CENTER
        }
        val initial = name.trim().firstOrNull()?.toString() ?: "?"
        val y = height / 2f - (p.descent() + p.ascent()) / 2f
        c.drawText(initial, width / 2f, y, p)
        return bmp
    }
}

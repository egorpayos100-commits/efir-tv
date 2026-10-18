package plus.efir.tv.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface

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
        val radius = height * 0.18f
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

        tileCache = bmp
        tileKey = signature
        return bmp
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

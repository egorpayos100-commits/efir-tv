package plus.efir.tv.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Loads tvg-logo artwork into list rows. No image library, just a cache and two threads. */
object LogoLoader {

    private const val TARGET_PX = 192
    private const val CACHE_BYTES = 6 * 1024 * 1024

    private val main = Handler(Looper.getMainLooper())
    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val queue = java.util.concurrent.LinkedBlockingQueue<Runnable>(48)

    init {
        repeat(2) {
            Thread({
                while (true) runCatching { queue.take().run() }
            }, "logo-io").apply { isDaemon = true; start() }
        }
    }

    fun into(view: ImageView, url: String?, fallback: Bitmap) {
        if (url.isNullOrBlank()) {
            view.tag = null
            view.setImageBitmap(fallback)
            return
        }
        view.tag = url
        val cached = cache.get(url)
        if (cached != null) {
            view.setImageBitmap(cached)
            return
        }
        view.setImageBitmap(fallback)
        queue.offer(Runnable {
            val bitmap = decode(url) ?: return@Runnable
            cache.put(url, bitmap)
            main.post { if (view.tag == url) view.setImageBitmap(bitmap) }
        })
    }

    private fun decode(url: String): Bitmap? = runCatching {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 4000
            readTimeout = 5000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", Tuner.DEFAULT_USER_AGENT)
        }
        val bytes = try {
            connection.inputStream.use { input ->
                ByteArrayOutputStream().also { output -> input.copyTo(output, 8192) }.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
        if (bytes.isEmpty()) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= TARGET_PX) sample *= 2
        BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample }
        )
    }.getOrNull()
}

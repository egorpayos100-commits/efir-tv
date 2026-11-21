package plus.efir.tv.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import android.util.LruCache
import android.widget.ImageView
import plus.efir.tv.data.Doh
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue

/** Loads tvg-logo artwork into list rows. No image library, just a cache and two threads. */
object LogoLoader {

    private const val DEFAULT_TARGET_PX = 192
    /**
     * A wall of posters is thirty-odd cards apiece: the old 12 MB budget evicted the ones he had
     * already passed, so scrolling back up showed a monogram and the poster popped in a moment
     * later. Photos are decoded as RGB_565 now, which halves what each of them really costs.
     */
    private const val CACHE_BYTES = 40 * 1024 * 1024
    private const val MAX_BYTES = 2 * 1024 * 1024

    /** A poster the TV's Wi-Fi dropped once is asked for again before the card stays a monogram. */
    private const val ATTEMPTS = 2
    private const val RETRY_MS = 1500L
    /** A host that just failed everywhere is left alone for a minute instead of holding the queue. */
    private const val DEAD_HOST_MS = 60_000L
    private const val HOST_FAILURES = 3

    private val main = Handler(Looper.getMainLooper())
    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    /**
     * Unbounded on purpose. The wall now holds fifty films and binds a screenful at a time; with a
     * cap of 48 the queue quietly dropped the tail, and those cards stayed monograms until they were
     * scrolled past again. One job per address is kept by [inFlight] instead.
     */
    private val queue = LinkedBlockingQueue<Runnable>()
    private val inFlight =
        ConcurrentHashMap<String, MutableList<WeakReference<ImageView>>>()
    /**
     * One unreachable logo host used to send every one of its pictures through two full timeouts,
     * and those jobs sat at the head of the queue while film posters waited behind them: the wall
     * came up as monograms. Three failures on the same host rest it for a minute — one broken
     * picture alone must not silence a whole picture host.
     */
    private val deadHosts = ConcurrentHashMap<String, Long>()
    private val hostFailures = ConcurrentHashMap<String, Int>()

    init {
        repeat(2) {
            Thread({
                // Two JPEG decodes at full priority outrun the UI thread on this box: the shelves
                // stuttered while posters were being read, which read as «the app itself is heavy».
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                while (true) runCatching { queue.take().run() }
            }, "logo-io").apply { isDaemon = true; start() }
        }
    }

    /**
     * `targetPx` is the width the picture is decoded for: a channel logo is 40dp wide, a film
     * card is a few hundred, and one shared size would blur one of them.
     */
    fun into(view: ImageView, url: String?, fallback: Bitmap, targetPx: Int = DEFAULT_TARGET_PX) {
        if (url.isNullOrBlank()) {
            view.tag = null
            view.setImageBitmap(fallback)
            return
        }
        val key = "$url|$targetPx"
        view.tag = key
        val cached = cache.get(key)
        if (cached != null) {
            view.setImageBitmap(cached)
            return
        }
        view.setImageBitmap(fallback)
        val host = runCatching { URL(url).host }.getOrNull().orEmpty()
        val failedAt = deadHosts[host]
        if (failedAt != null && System.currentTimeMillis() - failedAt < DEAD_HOST_MS) return
        // Rows scroll constantly: the job holds the view weakly, or a fast list would pin dead ones.
        val weak = WeakReference(view)
        val first = inFlight.putIfAbsent(
            key, Collections.synchronizedList(mutableListOf(weak))
        ) == null
        // A second card for the same picture joins the running job instead of asking again; it
        // still gets the bitmap when the job lands, otherwise it would wear a monogram forever.
        if (!first) inFlight[key]?.add(weak)
        if (first) queue.offer(Runnable { fetch(key, url, targetPx, 0) })
    }

    private fun fetch(key: String, url: String, targetPx: Int, attempt: Int) {
        val bitmap = decode(url, targetPx)
        if (bitmap == null) {
            if (attempt < ATTEMPTS) {
                main.postDelayed({
                    queue.offer(Runnable { fetch(key, url, targetPx, attempt + 1) })
                }, RETRY_MS)
            } else {
                inFlight.remove(key)
                val host = runCatching { URL(url).host }.getOrNull().orEmpty()
                if (host.isNotEmpty()) {
                    val fails = synchronized(hostFailures) {
                        ((hostFailures[host] ?: 0) + 1).also { hostFailures[host] = it }
                    }
                    if (fails >= HOST_FAILURES) {
                        hostFailures.remove(host)
                        deadHosts[host] = System.currentTimeMillis()
                    }
                }
                Log.w("LogoLoader", "не загрузился постер: $url")
            }
            return
        }
        runCatching { URL(url).host }.getOrNull()?.let {
            deadHosts.remove(it)
            hostFailures.remove(it)
        }
        cache.put(key, bitmap)
        val targets = inFlight.remove(key)
        main.post {
            targets?.forEach { ref ->
                val target = ref.get() ?: return@forEach
                if (target.tag == key) target.setImageBitmap(bitmap)
            }
        }
    }

    private fun decode(url: String, targetPx: Int): Bitmap? {
        decodeOnce(url, targetPx, null)?.let { return it }
        // Pinning to an address is a TLS trick, so it only helps an https picture. The provider's
        // filter answers some of those hosts with a block page or nothing at all.
        if (!url.startsWith("https://", ignoreCase = true)) return null
        val host = runCatching { URL(url).host }.getOrNull() ?: return null
        val ip = Doh.addressOf(host) ?: return null
        Log.i("LogoLoader", "постер через DoH: $host -> $ip")
        return decodeOnce(url, targetPx, ip)
    }

    private fun decodeOnce(url: String, targetPx: Int, ip: String?): Bitmap? = runCatching {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            // A TV on Wi-Fi sometimes takes a few seconds just to open a picture; the old four-second
            // connect gave up on the first hiccup and nothing ever asked again.
            connectTimeout = 7000
            readTimeout = 15000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", Tuner.DEFAULT_USER_AGENT)
        }
        Doh.pin(connection, ip)
        val bytes = try {
            connection.inputStream.use { input ->
                // A logo typed into a playlist can point at anything: read at most a couple of MB.
                val output = ByteArrayOutputStream(16 * 1024)
                val buffer = ByteArray(8192)
                var total = 0
                while (total < MAX_BYTES) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    total += read
                }
                output.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
        if (bytes.isEmpty()) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetPx) sample *= 2
        // A still or a poster covers its cell completely, so the fourth channel is memory for
        // nothing. A logo is often a PNG with transparency and keeps all four.
        val photo = bounds.outMimeType?.contains("jpeg", true) == true
        BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                if (photo) inPreferredConfig = Bitmap.Config.RGB_565
            }
        )
    }.getOrNull()
}

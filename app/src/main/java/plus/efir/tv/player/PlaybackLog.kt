package plus.efir.tv.player

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import androidx.media3.common.PlaybackException
import plus.efir.tv.data.Channel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Why each channel refused to play. ExoPlayer errors vanish with the logcat buffer, so the last
 * ones are kept on disk and read back on the info screen of the playlist pane.
 *
 * The player, the check pool and the resolver all write here, so the file is rewritten in one go
 * on its own thread: rewriting it per line was disk work in the middle of playback.
 */
object PlaybackLog {

    private const val FILE_NAME = "playback.log"
    private const val MAX_ENTRIES = 40
    private const val FLUSH_MS = 2_000L
    private const val HEARTBEAT_MARK = "жив"

    private val clock = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
    private val lines = ArrayDeque<String>()
    private val io = HandlerThread("playback-log").apply { start() }
    private val ioHandler = Handler(io.looper)
    private var file: File? = null
    private var flushQueued = false

    /** The last minute the process was alive. Kept out of the list so a long evening of beating
     *  cannot push the errors out of the file. */
    @Volatile
    private var heartbeat = ""

    fun attach(context: Context) {
        file = File(context.filesDir, FILE_NAME)
        // An old heartbeat that was the last line becomes ordinary history here; the live one of this
        // process is always re-appended at the end, so the moment the previous death happened stays readable.
        runCatching { file!!.readLines().takeLast(MAX_ENTRIES).forEach { lines.addLast(it) } }
    }

    fun record(channel: Channel, url: String?, error: PlaybackException) {
        val cause = error.cause?.javaClass?.simpleName?.let { " ← $it" } ?: ""
        add("${stamp()} · ${channel.name} · ${error.errorCodeName} (код ${error.errorCode})$cause\n" +
            "      ${url ?: "без адреса"}")
    }

    /** One line per background pass: which channels answered, instead of one line per address. */
    fun recordCheck(alive: Int, names: List<String>) {
        add("${stamp()} · проверка · живых $alive из таблицы\n      ${names.take(12).joinToString(", ")}")
    }

    /** What the channel's own player answered when the app asked it for an address. */
    fun recordResolve(name: String, url: String?, reason: String) {
        val entry = "${stamp()} · резолвер · $name · $reason"
        add(if (url == null) entry else "$entry\n      ${url.take(160)}")
    }

    /** A live address whose play head stopped moving: the player reports no error for that, so the
     *  watchdog writes the fact itself — otherwise «картинка есть, поток не идёт» leaves no trace. */
    fun recordStall(name: String, url: String?) {
        val entry = "${stamp()} · встало · $name · буфер не пополняется"
        add(if (url == null) entry else "$entry\n      ${url.take(160)}")
    }

    /** Screen opened / closed: the difference between «приставка уснула» and «процесс убили» is
     *  whether this line got written before the silence. */
    fun recordSession(text: String) {
        add("${stamp()} · сессия · $text")
    }

    /** Proof of life while the screen is up; overwrites the previous beat instead of growing the log. */
    fun beat(text: String) {
        heartbeat = "${stamp()} · $HEARTBEAT_MARK · $text"
        scheduleFlush()
    }

    private fun stamp(): String = synchronized(clock) { clock.format(Date()) }

    private fun add(entry: String) {
        synchronized(lines) {
            while (lines.size >= MAX_ENTRIES) lines.removeFirst()
            lines.addLast(entry)
        }
        scheduleFlush()
    }

    private fun scheduleFlush() {
        synchronized(this) {
            if (flushQueued) return
            flushQueued = true
        }
        ioHandler.postDelayed({
            val text = synchronized(lines) {
                (lines.toList() + listOfNotNull(heartbeat.takeIf { it.isNotEmpty() })).joinToString("\n")
            }
            synchronized(this) { flushQueued = false }
            runCatching { file?.writeText(text) }
        }, FLUSH_MS)
    }

    fun read(): String = synchronized(lines) {
        val all = lines.toList() + listOfNotNull(heartbeat.takeIf { it.isNotEmpty() })
        if (all.isEmpty()) "" else all.asReversed().joinToString("\n")
    }

    fun clear() {
        synchronized(lines) { lines.clear() }
        heartbeat = ""
        runCatching { file?.delete() }
    }
}

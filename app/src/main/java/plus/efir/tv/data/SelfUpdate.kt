package plus.efir.tv.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import plus.efir.tv.BuildConfig

/**
 * A new build reaches the box over Wi-Fi, so updating is one press on the remote instead of a walk
 * to the drawer with the stick.
 *
 * The version file is one line — `versionCode  file-name.apk` — and the APK sits beside it on the
 * same address. That address is his setting, the same way the guide's is, so a build can be put
 * anywhere without recompiling anything.
 */
class SelfUpdate(private val context: Context) {

    private val prefs = context.getSharedPreferences("efir", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())

    enum class State { Idle, Checking, Available, Current, Downloading, Failed }

    @Volatile var state: State = State.Idle
        private set

    /** What the server says the newest build is; 0 until a check has answered. */
    @Volatile var remoteVersion = 0
        private set

    /** Bytes read so far, and the size the server promised (-1 when it promised nothing). */
    @Volatile var downloadedBytes = 0L
        private set

    @Volatile var totalBytes = -1L
        private set

    @Volatile var failure: String? = null
        private set

    /** The whole version file, kept so picking the APK does not download it a second time. */
    @Volatile private var manifest = ""

    /** Fires on the UI thread whenever anything above changes. */
    var onUpdated: (() -> Unit)? = null

    val isBusy get() = state == State.Checking || state == State.Downloading

    /** True once a check found a build newer than the one running. */
    val isAvailable get() = state == State.Available

    fun url(): String = prefs.getString(KEY, DEFAULT_URL).orEmpty().trim()

    fun setUrl(value: String) {
        prefs.edit().putString(KEY, value.trim()).apply()
        manifest = ""
    }

    /** Reads the version file. False means nothing was queued: a check or a download is running. */
    fun check(): Boolean {
        if (isBusy) return false
        val address = url()
        if (!address.startsWith("http")) {
            failure = "адрес не введён"
            state = State.Failed
            notify()
            return false
        }
        state = State.Checking
        failure = null
        notify()
        Thread {
            manifest = runCatching { getText(address) }.getOrNull().orEmpty()
            publish(manifest, address)
        }.apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
        return true
    }

    /**
     * Pulls the APK and hands it to the system installer. "no-permission" means the box has not been
     * told this app may install anything at all; the row then opens the screen that unbars it rather
     * than pretending the update is on its way.
     */
    fun install(): String {
        if (isBusy) return "busy"
        if (remoteVersion <= BuildConfig.VERSION_CODE) return "current"
        if (!mayInstall()) return "no-permission"
        val apk = apkAddress()
        if (apk == null) return "no-file"
        state = State.Downloading
        failure = null
        downloadedBytes = 0L
        totalBytes = -1L
        notify()
        Thread {
            val outcome = runCatching { download(apk) }
            val file = outcome.getOrNull()
            if (file == null) {
                failure = outcome.exceptionOrNull()?.message ?: "не скачалось"
                state = State.Failed
                notify()
            } else {
                main.post { launchInstaller(file) }
            }
        }.apply { isDaemon = true }.start()
        return "downloading"
    }

    /** The system screen that lets this app install apps, for the boxes that bar it by default. */
    fun openPermissionSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    .setData(Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** Which of the three answers a press on the row can get, in the viewer's words. */
    fun describe(): String = when (state) {
        State.Idle -> "не проверялось"
        State.Checking -> "спрашиваю…"
        State.Downloading -> if (totalBytes > 0)
            "качаю ${downloadedBytes * 100 / totalBytes}%" else "качаю ${megabytes(downloadedBytes)}"
        State.Available -> "доступна ${versionName(remoteVersion)}"
        State.Current -> "эта сборка свежая"
        State.Failed -> "${failure ?: "не вышло"} · v${BuildConfig.VERSION_NAME}"
    }

    private fun publish(manifest: String, address: String) {
        val line = manifest.trim().lines().firstOrNull().orEmpty().trim()
        val onServer = line.split(WHITESPACE).firstOrNull()?.toIntOrNull()
        remoteVersion = onServer ?: 0
        state = when {
            onServer == null -> {
                failure = if (manifest.isBlank()) "нет связи" else "в файле нет номера сборки"
                State.Failed
            }
            onServer > BuildConfig.VERSION_CODE -> State.Available
            else -> State.Current
        }
        if (state == State.Available) Log.i(TAG, "update available: $onServer at $address")
        notify()
    }

    /** The build file lives next to the version file, under the name written inside it. */
    private fun apkAddress(): String? {
        val name = manifest.trim().lines().firstOrNull().orEmpty().trim()
            .split(WHITESPACE).getOrNull(1) ?: return null
        if (!name.endsWith(".apk") || name.contains('/')) return null
        val address = url()
        return if (name.startsWith("http")) name
        else address.substringBeforeLast('/', "") + "/" + name
    }

    private fun download(address: String): File {
        val dir = File(context.cacheDir, APK_DIR)
        runCatching { dir.listFiles()?.forEach { it.delete() } }
        dir.mkdirs()
        val temp = File(dir, "part.apk")
        val target = File(dir, "efir-$remoteVersion.apk")
        runCatching { temp.delete() }
        val bytes = getToFile(address, temp)
        if (bytes <= 0) throw IllegalStateException("пусто по этому адресу")
        if (!temp.renameTo(target)) throw IllegalStateException("не удалось сохранить файл")
        return target
    }

    private fun mayInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(true)

    /**
     * The installer cannot read a plain path since 7.0, so it gets a content URI with a one-time read
     * grant — which is also why the file lives in the provider's own folder, not anywhere in the cache.
     */
    private fun launchInstaller(file: File) {
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrNull()
        if (uri == null) {
            failure = "установщик не видит файл"
            state = State.Failed
            notify()
            return
        }
        val started = runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, MIME_APK)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.isSuccess
        if (!started) {
            failure = "установщик не открылся"
            state = State.Failed
        }
        notify()
    }

    /**
     * Follows a redirect across http and https the way the playlist reader does, and writes the body
     * straight to disk: a six megabyte APK read into an array would be a second copy of the app in
     * the heap of a box that has one gigabyte of it in total.
     */
    private fun open(address: String): HttpURLConnection? {
        var url = URL(address)
        repeat(MAX_REDIRECTS) {
            val connection = dial(url) ?: return null
            val code = runCatching { connection.responseCode }.getOrDefault(0)
            if (code in 300..399) {
                val target = connection.getHeaderField("Location")
                connection.disconnect()
                if (target == null) return null
                url = URL(url, target)
                return@repeat
            }
            if (code != 200) {
                connection.disconnect()
                return null
            }
            return connection
        }
        return null
    }

    /** Edge addresses first, so the provider's block-page answer never becomes the request. */
    private fun dial(url: URL): HttpURLConnection? {
        for (ip in Doh.dial(url.host)) {
            val connection = runCatching { url.openConnection() as HttpURLConnection }
                .getOrNull() ?: continue
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Cache-Control", "no-cache, no-store")
            Doh.pin(connection, ip)
            if (runCatching { connection.responseCode }.getOrDefault(0) != 0) return connection
            connection.disconnect()
        }
        return null
    }

    private fun getText(address: String): String? {
        val connection = open(if ('?' in address) address else "$address?${System.currentTimeMillis()}")
            ?: return null
        return try {
            val stream = if (connection.contentEncoding == "gzip")
                GZIPInputStream(connection.inputStream) else connection.inputStream
            stream.buffered().use { input ->
                val out = StringBuilder()
                val buffer = ByteArray(8192)
                var total = 0
                while (total < MAX_MANIFEST_BYTES) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.append(String(buffer, 0, read, Charsets.UTF_8))
                    total += read
                }
                out.toString()
            }
        } catch (error: Exception) {
            Log.w(TAG, "version file read failed", error)
            null
        } finally {
            connection.disconnect()
        }
    }

    /** Bytes written, or 0 when the address is not a file, too big, or the box has no room for it. */
    private fun getToFile(address: String, to: File): Long {
        val connection = open(address) ?: return 0
        totalBytes = connection.contentLength.toLong()
        return try {
            connection.inputStream.use { input ->
                FileOutputStream(to).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (total + read > MAX_APK_BYTES) {
                            runCatching { to.delete() }
                            failure = "файл больше ${MAX_APK_BYTES / 1_000_000} МБ"
                            notify()
                            return 0
                        }
                        output.write(buffer, 0, read)
                        total += read
                        if (total / STEP_BYTES != downloadedBytes / STEP_BYTES) {
                            downloadedBytes = total
                            notify()
                        }
                    }
                    output.flush()
                    total
                }
            }
        } catch (error: Exception) {
            Log.w(TAG, "apk download failed", error)
            runCatching { to.delete() }
            0
        } finally {
            connection.disconnect()
        }
    }

    private fun notify() = main.post { onUpdated?.invoke() }

    private fun megabytes(bytes: Long): String = "${bytes / 1_000_000} МБ"

    /** versionCode 70 is what he reads on screen as 0.70; from 100 the series turns over to 1.00. */
    private fun versionName(code: Int): String =
        if (code < 100) "0.$code" else "${code / 100}." + "%02d".format(code % 100)

    companion object {
        private const val TAG = "EfirUpdate"
        private const val KEY = "update_url"
        private const val APK_DIR = "updates"
        private const val MIME_APK = "application/vnd.android.package-archive"
        private const val MAX_MANIFEST_BYTES = 4096
        private const val MAX_APK_BYTES = 60_000_000L
        private const val STEP_BYTES = 120_000L
        private const val MAX_REDIRECTS = 5
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 30_000
        private val WHITESPACE = Regex("\\s+")
        private const val USER_AGENT = "EfirTV/1.0 (Android TV)"

        /** Where the newest build lives; the same host as his playlist. */
        private const val DEFAULT_URL =
            "https://raw.githubusercontent.com/egorpayos100-commits/tvlist/main/efir-version.txt"
    }
}

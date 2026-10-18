package plus.efir.tv

import android.content.Context
import java.io.File

object CrashReport {

    private const val FILE_NAME = "crash.log"
    private const val MAX_CHARS = 4000

    @Volatile
    var lastAction: String? = null

    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val where = lastAction?.let { " · последнее действие: $it" } ?: ""
                val text = buildString {
                    appendLine("Падение в потоке ${thread.name}$where")
                    appendThrowable(" ", error)
                    error.cause?.let { cause ->
                        appendLine("---- причина ----")
                        appendThrowable("  ", cause)
                        cause.cause?.let { appendThrowable("    ", it) }
                    }
                }
                file(context).writeText(text.take(MAX_CHARS))
            }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun StringBuilder.appendThrowable(indent: String, error: Throwable) {
        appendLine("${indent}${error.javaClass.name}: ${error.message}")
        error.stackTrace.take(10).forEach { appendLine("$indent  $it") }
    }

    fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun read(context: Context): String? =
        runCatching { file(context).readText().take(MAX_CHARS) }.getOrNull()

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }
}

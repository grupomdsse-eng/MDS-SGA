package com.grupomds.sga

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Registro local y muy ligero de fallos.
 *
 * No envía nada fuera del dispositivo. Su objetivo es que, si Android cierra el proceso por
 * una excepción no controlada, en el siguiente arranque podamos avisar y conservar el motivo.
 */
object AppCrashReporter {
    private const val PREFS = "sga_crash_reporter"
    private const val KEY_PENDING_FATAL = "pending_fatal"
    private const val KEY_FATAL_SUMMARY = "fatal_summary"
    private const val LOG_DIR = "diagnostics"
    private const val LOG_FILE = "sga_crash.log"
    private const val MAX_LOG_BYTES = 512 * 1024L

    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            val appContext = context.applicationContext
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                runCatching {
                    val summary = buildSummary("FATAL", thread.name, throwable)
                    appendLog(appContext, summary)
                    appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean(KEY_PENDING_FATAL, true)
                        .putString(KEY_FATAL_SUMMARY, shortThrowable(throwable))
                        .commit()
                }
                previous?.uncaughtException(thread, throwable)
            }
            installed = true
        }
    }

    fun recordHandled(context: Context, source: String, throwable: Throwable) {
        runCatching {
            appendLog(
                context.applicationContext,
                buildSummary("CONTROLADO:$source", Thread.currentThread().name, throwable)
            )
        }
    }

    /** Devuelve un aviso una sola vez, en el primer arranque posterior a un cierre fatal. */
    fun consumePreviousFatalNotice(context: Context): String? {
        return runCatching {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(KEY_PENDING_FATAL, false)) return@runCatching null
            val reason = prefs.getString(KEY_FATAL_SUMMARY, null).orEmpty()
            prefs.edit().putBoolean(KEY_PENDING_FATAL, false).apply()
            buildString {
                append("La sesión anterior se cerró por un error interno")
                if (reason.isNotBlank()) append(": $reason")
                append(". Se ha guardado un diagnóstico local para poder identificarlo si vuelve a ocurrir.")
            }
        }.getOrNull()
    }

    fun diagnosticsFile(context: Context): File = File(
        File(context.applicationContext.filesDir, LOG_DIR).apply { mkdirs() },
        LOG_FILE
    )

    private fun appendLog(context: Context, entry: String) {
        val file = diagnosticsFile(context)
        if (file.exists() && file.length() > MAX_LOG_BYTES) {
            val backup = File(file.parentFile, "sga_crash.previous.log")
            runCatching { backup.delete() }
            runCatching { file.renameTo(backup) }
        }
        file.appendText(entry + "\n\n", Charsets.UTF_8)
    }

    private fun buildSummary(kind: String, thread: String, throwable: Throwable): String {
        val stack = StringWriter().also { writer ->
            PrintWriter(writer).use { throwable.printStackTrace(it) }
        }.toString()
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        return """
            ===== SGA MDS $kind =====
            Fecha: $timestamp
            Hilo: $thread
            Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})
            Dispositivo: ${Build.MANUFACTURER} ${Build.MODEL}
            App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})
            ${stack.trim()}
        """.trimIndent()
    }

    private fun shortThrowable(throwable: Throwable): String {
        val type = throwable.javaClass.simpleName.ifBlank { "Error" }
        val message = throwable.message?.lineSequence()?.firstOrNull()?.take(180).orEmpty()
        return if (message.isBlank()) type else "$type · $message"
    }
}

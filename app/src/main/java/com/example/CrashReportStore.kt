package com.example

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Global startup & runtime crash capture store (`Thread.setDefaultUncaughtExceptionHandler`).
 *
 * Persists full uncaught exception stacktraces to internal storage (`filesDir/last_startup_crash.txt`)
 * so that if anything ever throws during launch or native initialization, the next launch immediately
 * displays a copyable diagnostic dialog instead of failing silently.
 */
object CrashReportStore {

    private const val TAG = "CrashReportStore"
    private const val CRASH_FILE_NAME = "last_startup_crash.txt"
    private val installed = AtomicBoolean(false)

    fun installUncaughtExceptionHandler(context: Context) {
        if (!installed.compareAndSet(false, true)) return
        val appCtx = context.applicationContext
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrashReportSync(appCtx, thread.name, throwable)
            } catch (ioErr: Throwable) {
                Log.e(TAG, "Failed writing crash report: ${ioErr.message}")
            } finally {
                if (previousHandler != null && previousHandler !== Thread.getDefaultUncaughtExceptionHandler()) {
                    previousHandler.uncaughtException(thread, throwable)
                }
            }
        }
        Log.i(TAG, "Installed global uncaught exception handler.")
    }

    fun writeCrashReportSync(context: Context, threadName: String, throwable: Throwable) {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        throwable.printStackTrace(pw)
        pw.flush()

        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val report = buildString {
            appendLine("=== CLEANBOOK CRASH DIAGNOSTIC REPORT ===")
            appendLine("Time: $timestamp")
            appendLine("Thread: $threadName")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (SDK ${Build.VERSION.SDK_INT} / Android ${Build.VERSION.RELEASE})")
            appendLine("ABIs: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
            appendLine("Exception: ${throwable.javaClass.name}: ${throwable.message}")
            appendLine()
            appendLine("--- STACKTRACE ---")
            append(sw.toString())
        }

        val target = File(context.applicationContext.filesDir, CRASH_FILE_NAME)
        target.parentFile?.mkdirs()
        target.writeText(report, Charsets.UTF_8)
        Log.e(TAG, "Saved crash report (${report.length} chars) to ${target.absolutePath}", throwable)
    }

    fun readPendingCrashReport(context: Context): String? {
        return runCatching {
            val file = File(context.applicationContext.filesDir, CRASH_FILE_NAME)
            if (!file.exists() || file.length() <= 0L) null
            else file.readText(Charsets.UTF_8).takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    fun clearCrashReport(context: Context) {
        runCatching {
            val file = File(context.applicationContext.filesDir, CRASH_FILE_NAME)
            if (file.exists()) file.delete()
        }
    }
}

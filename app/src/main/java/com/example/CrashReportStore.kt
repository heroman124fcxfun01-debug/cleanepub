package com.example

import android.app.ActivityManager
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
 * Global startup, runtime & native-operation crash capture store:
 * 1. Installs `Thread.setDefaultUncaughtExceptionHandler` to persist Java/Kotlin stacktraces to
 *    `filesDir/last_startup_crash.txt`.
 * 2. Provides synchronous native-operation breadcrumbs (`markNativeOperationStart` / `clearNativeOperation`)
 *    around C++/JNI calls (`llama.cpp` GGUF, `onnxruntime`). If a native signal (`SIGILL`/`SIGSEGV`/OOM)
 *    kills the process (bypassing Java's UncaughtExceptionHandler), the next launch detects the leftover
 *    breadcrumb and displays the exact failing native step, ABI, CPU features, and memory state.
 */
object CrashReportStore {

    private const val TAG = "CrashReportStore"
    private const val CRASH_FILE_NAME = "last_startup_crash.txt"
    private const val NATIVE_BREADCRUMB_FILE = "last_native_op.txt"
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
            appendLine("RAM: ${formatMemorySummary(context)}")
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

    /**
     * Writes a synchronous breadcrumb before entering a native C++/JNI call.
     * Must be paired with [clearNativeOperation] in a `finally` block.
     */
    fun markNativeOperationStart(context: Context?, operation: String, detail: String = "") {
        val ctx = context?.applicationContext ?: return
        runCatching {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            val content = buildString {
                appendLine("=== CLEANBOOK NATIVE CRASH / OOM REPORT ===")
                appendLine("Ứng dụng bị hệ điều hành đóng đột ngột (tín hiệu Native SIGILL/SIGSEGV hoặc tràn RAM) khi đang chạy bước sau:")
                appendLine()
                appendLine("Bước Native đang chạy: $operation")
                if (detail.isNotBlank()) {
                    appendLine("Chi tiết: $detail")
                }
                appendLine("Thời điểm: $timestamp")
                appendLine("Thiết bị: ${Build.MANUFACTURER} ${Build.MODEL} (SDK ${Build.VERSION.SDK_INT} / Android ${Build.VERSION.RELEASE})")
                appendLine("ABIs: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
                appendLine("CPU Features: ${readCpuFeaturesLine().ifBlank { "(không đọc được /proc/cpuinfo)" }}")
                appendLine("RAM: ${formatMemorySummary(ctx)}")
            }
            val file = File(ctx.filesDir, NATIVE_BREADCRUMB_FILE)
            file.parentFile?.mkdirs()
            file.writeText(content, Charsets.UTF_8)
        }
    }

    fun clearNativeOperation(context: Context?) {
        val ctx = context?.applicationContext ?: return
        runCatching {
            val file = File(ctx.filesDir, NATIVE_BREADCRUMB_FILE)
            if (file.exists()) file.delete()
        }
    }

    fun readPendingCrashReport(context: Context): String? {
        return runCatching {
            val appDir = context.applicationContext.filesDir
            val javaCrashFile = File(appDir, CRASH_FILE_NAME)
            if (javaCrashFile.exists() && javaCrashFile.length() > 0L) {
                val text = javaCrashFile.readText(Charsets.UTF_8).trim()
                if (text.isNotEmpty()) return@runCatching text
            }
            val nativeFile = File(appDir, NATIVE_BREADCRUMB_FILE)
            if (nativeFile.exists() && nativeFile.length() > 0L) {
                val text = nativeFile.readText(Charsets.UTF_8).trim()
                if (text.isNotEmpty()) return@runCatching text
            }
            null
        }.getOrNull()
    }

    fun clearCrashReport(context: Context) {
        runCatching {
            val appDir = context.applicationContext.filesDir
            File(appDir, CRASH_FILE_NAME).let { if (it.exists()) it.delete() }
            File(appDir, NATIVE_BREADCRUMB_FILE).let { if (it.exists()) it.delete() }
        }
    }

    fun readCpuFeaturesLine(): String {
        return runCatching {
            File("/proc/cpuinfo").bufferedReader().use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val current = line ?: continue
                    if (current.startsWith("Features")) {
                        return@use current.trim()
                    }
                }
                ""
            }
        }.getOrDefault("")
    }

    private fun formatMemorySummary(context: Context): String {
        return runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val mi = ActivityManager.MemoryInfo()
            if (am != null) {
                am.getMemoryInfo(mi)
                val availMb = mi.availMem / (1024L * 1024L)
                val totalMb = mi.totalMem / (1024L * 1024L)
                "avail=${availMb}MB / total=${totalMb}MB (lowMemory=${mi.lowMemory})"
            } else {
                val rt = Runtime.getRuntime()
                "heapMax=${rt.maxMemory() / (1024L * 1024L)}MB"
            }
        }.getOrDefault("unknown")
    }
}

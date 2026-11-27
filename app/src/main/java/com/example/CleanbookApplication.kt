package com.example

import android.app.Application

/**
 * Lightweight Application entry point:
 * - Installs global uncaught exception handler immediately so any startup error is captured & displayed.
 * - Intentionally performs ZERO AI model copying, ZERO native library loading (`llama.cpp`/`onnxruntime`),
 *   and ZERO heavy disk I/O on the Main Thread during app launch.
 */
class CleanbookApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        CrashReportStore.installUncaughtExceptionHandler(this)
    }
}

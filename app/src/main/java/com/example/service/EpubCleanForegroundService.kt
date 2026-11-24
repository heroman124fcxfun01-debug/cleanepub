package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.pipeline.EpubCleanPipeline
import com.example.pipeline.InputFileType
import com.example.pipeline.PipelineStep
import com.example.pipeline.ProcessingPhase
import com.example.pipeline.ProcessingSnapshot
import com.example.pipeline.SourceFileInfo
import com.example.pipeline.StepState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val TAG = "EpubCleanService"
private const val CHANNEL_ID = "epub_clean_progress_channel"
private const val NOTIFICATION_ID = 4102

class EpubCleanForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeJob: Job? = null
    private var lastNotificationUpdateMs = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                activeJob?.cancel()
                val current = ProcessingStateHolder.snapshot.value
                ProcessingStateHolder.publish(
                    current.copy(
                        phase = ProcessingPhase.CANCELLED,
                        statusDetail = "Đã hủy quá trình xử lý."
                    )
                )
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                val source = extractSourceFileInfo(intent) ?: run {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForegroundWithNotification(
                    title = getString(R.string.notif_title_running, source.displayName),
                    detail = "Đang khởi tạo quy trình 5 bước…",
                    progressPercent = 0
                )
                launchPipeline(source)
            }
        }
        return START_NOT_STICKY
    }

    private fun launchPipeline(source: SourceFileInfo) {
        activeJob?.cancel()
        val pipeline = EpubCleanPipeline(applicationContext)

        activeJob = serviceScope.launch {
            try {
                pipeline.run(source) { snapshot ->
                    ProcessingStateHolder.publish(snapshot)
                    val now = System.currentTimeMillis()
                    if (now - lastNotificationUpdateMs >= 450L || snapshot.phase == ProcessingPhase.COMPLETED) {
                        lastNotificationUpdateMs = now
                        val pct = (snapshot.progressFraction * 100).toInt().coerceIn(0, 100)
                        val statsSummary = "${snapshot.statusDetail} • " +
                            "Link: ${snapshot.stats.junkLinksRemoved} | " +
                            "Trắng: ${snapshot.stats.whitespaceFixes} | " +
                            "AI: ${snapshot.stats.spellingFixes}"
                        updateNotification(
                            title = if (snapshot.phase == ProcessingPhase.COMPLETED) {
                                getString(R.string.notif_title_done, source.displayName)
                            } else {
                                getString(R.string.notif_title_running, source.displayName)
                            },
                            detail = statsSummary,
                            progressPercent = pct,
                            ongoing = snapshot.phase == ProcessingPhase.RUNNING
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Pipeline execution failed", e)
                val prev = ProcessingStateHolder.snapshot.value
                val failedSteps = prev.stepStates.toMutableMap().apply {
                    put(prev.currentStep, StepState.FAILED)
                }
                ProcessingStateHolder.publish(
                    prev.copy(
                        phase = ProcessingPhase.ERROR,
                        stepStates = failedSteps,
                        errorMessage = e.localizedMessage ?: "Đã xảy ra lỗi khi xử lý sách."
                    )
                )
            } finally {
                stopForegroundCompat()
                stopSelf()
            }
        }
    }

    private fun startForegroundWithNotification(title: String, detail: String, progressPercent: Int) {
        val notification = buildNotification(title, detail, progressPercent, ongoing = true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(title: String, detail: String, progressPercent: Int, ongoing: Boolean) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(title, detail, progressPercent, ongoing))
    }

    private fun buildNotification(
        title: String,
        detail: String,
        progressPercent: Int,
        ongoing: Boolean
    ): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPending = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = Intent(this, EpubCleanForegroundService::class.java).apply {
            action = ACTION_CANCEL
        }
        val cancelPending = PendingIntent.getService(
            this,
            1,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setContentIntent(contentPending)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (ongoing) {
            builder.setProgress(100, progressPercent, progressPercent == 0)
            builder.addAction(0, getString(R.string.notif_action_cancel), cancelPending)
        } else {
            builder.setProgress(0, 0, false)
            builder.setAutoCancel(true)
        }
        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notif_channel_desc)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun onDestroy() {
        activeJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun extractSourceFileInfo(intent: Intent): SourceFileInfo? {
        val displayName = intent.getStringExtra(EXTRA_DISPLAY_NAME) ?: return null
        val uriString = intent.getStringExtra(EXTRA_URI) ?: return null
        val fileTypeName = intent.getStringExtra(EXTRA_FILE_TYPE) ?: InputFileType.EPUB.name
        return SourceFileInfo(
            displayName = displayName,
            sourceUriString = uriString,
            resolvedFilePath = intent.getStringExtra(EXTRA_RESOLVED_PATH),
            resolvedParentDir = intent.getStringExtra(EXTRA_RESOLVED_PARENT_DIR),
            fileType = runCatching { InputFileType.valueOf(fileTypeName) }.getOrDefault(InputFileType.EPUB),
            fileSizeBytes = intent.getLongExtra(EXTRA_FILE_SIZE, 0L)
        )
    }

    companion object {
        private const val ACTION_START = "com.example.service.ACTION_START_CLEAN"
        private const val ACTION_CANCEL = "com.example.service.ACTION_CANCEL_CLEAN"

        private const val EXTRA_DISPLAY_NAME = "extra_display_name"
        private const val EXTRA_URI = "extra_uri"
        private const val EXTRA_RESOLVED_PATH = "extra_resolved_path"
        private const val EXTRA_RESOLVED_PARENT_DIR = "extra_resolved_parent_dir"
        private const val EXTRA_FILE_TYPE = "extra_file_type"
        private const val EXTRA_FILE_SIZE = "extra_file_size"

        fun start(context: Context, source: SourceFileInfo) {
            ProcessingStateHolder.publish(
                ProcessingSnapshot(
                    phase = ProcessingPhase.RUNNING,
                    sourceFile = source,
                    currentStep = PipelineStep.STEP_0_UNPACK_CONTEXT,
                    stepStates = PipelineStep.entries.associateWith {
                        if (it == PipelineStep.STEP_0_UNPACK_CONTEXT) StepState.RUNNING else StepState.PENDING
                    },
                    progressFraction = 0.02f,
                    statusDetail = "Đang khởi động dịch vụ chạy nền…"
                )
            )
            val intent = Intent(context, EpubCleanForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_DISPLAY_NAME, source.displayName)
                putExtra(EXTRA_URI, source.sourceUriString)
                putExtra(EXTRA_RESOLVED_PATH, source.resolvedFilePath)
                putExtra(EXTRA_RESOLVED_PARENT_DIR, source.resolvedParentDir)
                putExtra(EXTRA_FILE_TYPE, source.fileType.name)
                putExtra(EXTRA_FILE_SIZE, source.fileSizeBytes)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun cancel(context: Context) {
            val intent = Intent(context, EpubCleanForegroundService::class.java).apply {
                action = ACTION_CANCEL
            }
            context.startService(intent)
        }
    }
}

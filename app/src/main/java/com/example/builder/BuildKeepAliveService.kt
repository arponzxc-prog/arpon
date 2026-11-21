package com.example.builder

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
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity

/**
 * Pure (JVM-testable) helpers for the background-build notification text.
 * Never receives logs, tokens or other secrets - only project name, step and percent.
 */
object BuildNotificationText {
    fun progressTitle(projectName: String): String =
        "Nexa IDE - Building " + projectName.trim().ifEmpty { "project" }

    fun progressText(step: String, percent: Float): String {
        val p = (percent.coerceIn(0f, 1f) * 100f).toInt()
        val s = step.trim().ifEmpty { "Working..." }
        return s + " (" + p + "%)"
    }

    /** true when a foreground service must be running for this state. */
    fun needsService(state: BuildProgressState): Boolean = state is BuildProgressState.Active
}

/**
 * Foreground service that keeps the process alive while a cloud build is running,
 * so the build continues even after the user leaves / swipes away the app UI.
 * The actual work is done by [BuildEngine] (process-wide singleton); this service only
 * holds the foreground priority and shows progress.
 */
class BuildKeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }
        val title = intent?.getStringExtra(EXTRA_TITLE).orEmpty()
        val text = intent?.getStringExtra(EXTRA_TEXT).orEmpty()
        val notification = buildProgressNotification(this, title, text)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: Exception) {
            // Not allowed to start foreground right now; build still runs while process lives.
            stopSelf()
        }
        return START_NOT_STICKY
    }

    /** Android 15: dataSync foreground services have a time limit; stop cleanly instead of crashing. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopForegroundCompat()
        stopSelf()
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION") stopForeground(true)
        }
    }

    companion object {
        const val CHANNEL_ID = "nexa_build_progress"
        const val RESULT_CHANNEL_ID = "nexa_build_result"
        const val NOTIFICATION_ID = 7101
        const val RESULT_NOTIFICATION_ID = 7102
        const val ACTION_STOP = "com.example.builder.ACTION_STOP_BUILD_SERVICE"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"

        fun ensureChannels(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Build progress", NotificationManager.IMPORTANCE_LOW)
            )
            nm.createNotificationChannel(
                NotificationChannel(RESULT_CHANNEL_ID, "Build results", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }

        private fun contentIntent(context: Context): PendingIntent {
            val launch = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            return PendingIntent.getActivity(
                context, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        fun buildProgressNotification(context: Context, title: String, text: String): Notification {
            ensureChannels(context)
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(contentIntent(context))
                .build()
        }

        /** Start or update the foreground notification. Safe to call repeatedly. */
        fun startOrUpdate(context: Context, state: BuildProgressState.Active) {
            val intent = Intent(context, BuildKeepAliveService::class.java)
                .putExtra(EXTRA_TITLE, BuildNotificationText.progressTitle(state.projectName))
                .putExtra(EXTRA_TEXT, BuildNotificationText.progressText(state.currentStep, state.progressPercent))
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (_: Exception) {
                // e.g. started from background on Android 12+; build keeps running while process lives.
            }
        }

        fun stop(context: Context) {
            try {
                context.startService(
                    Intent(context, BuildKeepAliveService::class.java).setAction(ACTION_STOP)
                )
            } catch (_: Exception) {
                context.stopService(Intent(context, BuildKeepAliveService::class.java))
            }
        }

        /** Final success/failure notification so the user knows even if the app is closed. */
        fun notifyResult(context: Context, title: String, text: String) {
            try {
                ensureChannels(context)
                val n = NotificationCompat.Builder(context, RESULT_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle(title)
                    .setContentText(text.take(200))
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text.take(400)))
                    .setAutoCancel(true)
                    .setContentIntent(contentIntent(context))
                    .build()
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(RESULT_NOTIFICATION_ID, n)
            } catch (_: SecurityException) {
                // POST_NOTIFICATIONS not granted - in-app notification entry still exists.
            }
        }
    }
}

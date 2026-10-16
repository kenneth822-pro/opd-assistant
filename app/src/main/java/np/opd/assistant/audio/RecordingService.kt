package np.opd.assistant.audio

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
import androidx.core.app.ServiceCompat
import np.opd.assistant.MainActivity

/**
 * Keeps the microphone alive while a consultation is running.
 *
 * On Android 14/15 (targetSdk 35) the OS silences an inactive app's AudioRecord within seconds
 * of it leaving the foreground unless a microphone foreground service is running. This lightweight
 * service shows a persistent notification so recording survives switching to a lab PDF, calculator,
 * or chat app, or the screen turning off.
 */
class RecordingService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // Background-start restrictions / permission denial must never crash a live consultation.
            android.util.Log.w(TAG, "Could not start foreground service: ${e.message}")
        }
        // The ViewModel owns the AudioRecord. Restarting this notification after the app process
        // has been killed would falsely suggest that audio is still being captured.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "stopForeground failed: ${e.message}")
        }
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Active Consultation",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shown while a consultation is being recorded"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val open = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            this,
            0,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("OPD Assistant: Recording consultation…")
            .setContentText("Recording in progress. Keep this notification to continue in the background.")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(pending)
            .build()
    }

    companion object {
        private const val TAG = "RecordingService"
        private const val CHANNEL_ID = "opd_recording"
        private const val NOTIFICATION_ID = 1001

        /** Starts (or re-asserts) the foreground recording service. Never throws. */
        fun start(context: Context) {
            try {
                val intent = Intent(context, RecordingService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Could not start recording service: ${e.message}")
            }
        }

        /** Stops the foreground recording service. Never throws. */
        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, RecordingService::class.java))
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Could not stop recording service: ${e.message}")
            }
        }
    }
}

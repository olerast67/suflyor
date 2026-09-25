package com.olerast.suflyor.session

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
import com.olerast.suflyor.MainActivity
import com.olerast.suflyor.R
import com.olerast.suflyor.diag.DiagLog
import com.olerast.suflyor.overlay.OverlayHost

/**
 * Foreground service of type "microphone". Started while our activity is visible, it keeps microphone access
 * after the user switches to Instagram / TikTok / the camera. The session itself lives in [SessionEngine].
 */
class SessionService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            OverlayHost.stopSession(this)
            stopSelf()
            return START_NOT_STICKY
        }
        val nm = getSystemService(NotificationManager::class.java)
        // Re-created on every start with the same id: this also renames the channel after a language change.
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notification_channel_session), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, SessionService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(getString(R.string.notification_session_title))
            .setContentText(getString(R.string.notification_session_text))
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, getString(R.string.notification_action_stop), stop).build())
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(ID, notification)
            }
            foreground = true
        } catch (e: Exception) {
            DiagLog.e("Couldn't start the microphone foreground service", e)
            stopSelf()
            return START_NOT_STICKY
        }
        // The session ended before this start command arrived (OverlayHost does not stop a service that hasn't
        // called startForeground yet): leave now that it is allowed.
        if (!OverlayHost.active) {
            foreground = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        foreground = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL = "session"
        const val ID = 1
        const val ACTION_STOP = "com.olerast.suflyor.STOP"

        /**
         * A started instance has called startForeground() and hasn't been asked to stop yet. Cleared as soon as a stop
         * is requested, so a newer instance that is still waiting for its onStartCommand is never stopped from outside
         * (it stops itself there instead).
         */
        @Volatile
        var foreground = false
            private set

        /** Stops the service if it may be stopped now; otherwise its pending onStartCommand does it. */
        fun stop(context: Context) {
            if (!foreground) return
            foreground = false
            context.stopService(Intent(context, SessionService::class.java))
        }
    }
}

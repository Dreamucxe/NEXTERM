package com.nexterm.service

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
import com.nexterm.MainActivity
import com.nexterm.R
import com.nexterm.core.common.ApplicationScope
import com.nexterm.core.terminal.TerminalSessionManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps user-started shells alive while NEXTERM is in the background.
 *
 * Android will freeze and eventually kill a backgrounded process, taking every PTY
 * child with it; a foreground service with a visible notification is the only
 * supported way to say "the user asked for this to keep running". The service owns
 * no sessions of its own — [TerminalSessionManager] does — it simply lives as long
 * as at least one session is running and stops itself when the last one exits, so
 * an idle app never holds a permanent notification.
 */
@AndroidEntryPoint
class TerminalForegroundService : Service() {

    @Inject lateinit var sessionManager: TerminalSessionManager

    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_ALL) {
            sessionManager.closeAll()
            stopSelfSafely()
            return START_NOT_STICKY
        }

        startForegroundCompat(notification(sessionManager.runningCount))

        if (watcher == null) {
            watcher = scope.launch {
                sessionManager.states.collectLatest { states ->
                    val running = states.values.count { it.isRunning }
                    if (running == 0) {
                        // Nothing left to keep alive. Holding the notification here
                        // would be the dishonest kind of persistence.
                        stopSelfSafely()
                    } else {
                        notificationManager().notify(NOTIFICATION_ID, notification(running))
                    }
                }
            }
        }

        // START_STICKY would have Android restart this service with a null intent
        // after a kill, but the PTYs died with the process — there would be nothing
        // to keep alive, only an empty notification.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        watcher?.cancel()
        watcher = null
        super.onDestroy()
    }

    /**
     * The task being swiped away is a deliberate "I am done" from the user.
     *
     * Sessions are ended rather than orphaned behind an invisible notification.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        sessionManager.closeAll()
        stopSelfSafely()
        super.onTaskRemoved(rootIntent)
    }

    private fun stopSelfSafely() {
        watcher?.cancel()
        watcher = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun notification(running: Int): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopAll = PendingIntent.getService(
            this,
            1,
            Intent(this, TerminalForegroundService::class.java).setAction(ACTION_STOP_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (running == 1) "1 session running" else "$running sessions running")
            .setContentText("NEXTERM is keeping your shells alive.")
            .setContentIntent(open)
            .addAction(0, "Stop all", stopAll)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Running sessions",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shown while terminal sessions are kept alive in the background."
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        notificationManager().createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "nexterm_sessions"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_STOP_ALL = "com.nexterm.action.STOP_ALL"

        /** Starts the keeper if it is not already running. Safe to call repeatedly. */
        fun ensureRunning(context: Context) {
            val intent = Intent(context, TerminalForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TerminalForegroundService::class.java))
        }
    }
}

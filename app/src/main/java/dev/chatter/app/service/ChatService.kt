package dev.chatter.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.chatter.app.ChatterApp
import dev.chatter.app.R
import dev.chatter.app.auth.AuthState
import dev.chatter.app.irc.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Keeps the process and the chat WebSocket alive in the background so mentions arrive in real time.
 * Besides its notification it does nothing; the idle socket costs little battery.
 */
class ChatService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val container get() = (application as ChatterApp).container

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            val notification = buildNotification(0, ConnectionState.Connecting)
            // The special-use type exists since Android 14; Android 13 takes the service without a
            // type.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // Starting from the background is not always allowed (e.g. sticky restart). The
            // notification is the only way the user learns that mentions stopped.
            Log.w(TAG, "startForeground failed: ${e.message}")
            container.notifier.notifyNotListening(R.string.notif_not_listening_service)
            stopSelf()
            return
        }

        container.notifier.clearNotListening()
        container.backgroundHealth.setListening(true)
        container.connect()

        // With the app in front, a notification would cover what the user is reading; they get a
        // vibration and the unread counts instead.
        val appInFront = container.chat.windows.anyVisible
        scope.launch {
            container.chat.mentionEvents.collect {
                if (appInFront.value) container.notifier.buzz(ChatNotifier.mentionChannelId(it.channel))
                else container.notifier.notify(it)
            }
        }
        scope.launch {
            container.chat.whisperEvents.collect {
                if (appInFront.value) container.notifier.buzz(ChatNotifier.CHANNEL_WHISPERS)
                else container.notifier.notifyWhisper(it)
            }
        }
        // Runs while someone is logged in with at least one channel. Logging out keeps the
        // channels, so the login is watched as well. A guest gets no mentions or whispers and needs
        // no service.
        scope.launch {
            combine(
                container.channels.channels,
                container.irc.state,
                container.auth.state,
            ) { ch, st, auth -> Triple(ch.size, st, auth) }
                .distinctUntilChanged()
                .collect { (count, state, auth) ->
                    if (count == 0 || state == ConnectionState.AuthFailed || auth is AuthState.LoggedOut || auth is AuthState.Guest) {
                        // Logout and a rejected login close the socket themselves; running out of
                        // channels does not.
                        if (count == 0) container.disconnect()
                        stopSelf()
                    } else {
                        updateNotification(buildNotification(count, state))
                    }
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            container.disconnect()
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        // Without the service Android may end the process next, and up to ten minutes of statistics
        // are not saved yet.
        container.stats.saveNow()
        // Stopped on purpose: later process ends cost no mentions.
        container.backgroundHealth.setListening(false)
        scope.cancel()
        super.onDestroy()
    }

    private fun updateNotification(n: Notification) {
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, n)
        } catch (e: SecurityException) {
            // Without notification permission the service keeps running.
        }
    }

    private fun buildNotification(channelCount: Int, state: ConnectionState): Notification {
        val text = when (state) {
            ConnectionState.Connected -> resources.getQuantityString(R.plurals.notif_connected, channelCount, channelCount)
            ConnectionState.WaitingForNetwork -> getString(R.string.notif_waiting_network)
            else -> getString(R.string.notif_connecting)
        }
        val disconnect = PendingIntent.getService(
            this, 1, Intent(this, ChatService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, ChatNotifier.CHANNEL_CONNECTION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(ChatNotifier.openChannelIntent(this, null))
            .addAction(0, getString(R.string.notif_disconnect), disconnect)
            .build()
    }

    companion object {
        private const val TAG = "ChatService"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_DISCONNECT = "dev.chatter.app.DISCONNECT"

        /** Must be called while the app is in the foreground. */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, ChatService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "Could not start service: ${e.message}")
            }
        }
    }
}

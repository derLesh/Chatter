package dev.chatter.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
 * Keeps the process (and with it the chat WebSocket) alive while the app is in the background,
 * so mentions arrive in real time. It does no work of its own besides updating its notification
 * and forwarding mention events — the socket is idle most of the time, which costs very little battery.
 */
class ChatService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val container get() = (application as ChatterApp).container

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            startForeground(NOTIFICATION_ID, buildNotification(0, ConnectionState.Connecting), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } catch (e: Exception) {
            // Starting from the background is not allowed in some situations (e.g. sticky restart).
            // Nothing else would ever say so: the app is not on screen, and the only sign would be
            // mentions that stop arriving.
            Log.w(TAG, "startForeground failed: ${e.message}")
            container.notifier.notifyNotListening(R.string.notif_not_listening_service)
            stopSelf()
            return
        }

        container.notifier.clearNotListening()
        container.connect()

        // With the app in front, on another channel or another screen, a notification would only
        // cover what the user is reading; they get a buzz and the unread counts instead.
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
        // What the service is for, and therefore what ends it: somebody logged in with at least one
        // channel joined. Logging out leaves the channels in place, so without watching the login
        // as well the service would sit there for ever, saying "connecting" about nothing.
        scope.launch {
            combine(
                container.channels.channels,
                container.irc.state,
                container.auth.state,
            ) { ch, st, auth -> Triple(ch.size, st, auth) }
                .distinctUntilChanged()
                .collect { (count, state, auth) ->
                    if (count == 0 || state == ConnectionState.AuthFailed || auth is AuthState.LoggedOut) {
                        // Logging out and a rejected login close the socket themselves; running
                        // out of channels did not, and left it reconnecting in a process that has
                        // nothing left to listen for.
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
        scope.cancel()
        super.onDestroy()
    }

    private fun updateNotification(n: Notification) {
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, n)
        } catch (e: SecurityException) {
            // No notification permission: the foreground service keeps running anyway.
        }
    }

    private fun buildNotification(channelCount: Int, state: ConnectionState): Notification {
        val text = when (state) {
            ConnectionState.Connected -> resources.getQuantityString(R.plurals.notif_connected, channelCount, channelCount)
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

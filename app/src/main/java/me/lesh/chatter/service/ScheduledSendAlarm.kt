package me.lesh.chatter.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import me.lesh.chatter.ChatterApp
import kotlinx.coroutines.launch

/**
 * Wakes the phone for planned messages while Chatter is in the background. Inexact on purpose: an
 * exact alarm needs a permission the user has to grant by hand, and a few minutes either way are
 * fine there. With Chatter open the planned messages wait without it.
 */
class ScheduledSendAlarm(private val context: Context) {
    private val alarms = context.getSystemService(AlarmManager::class.java)

    /** Rings at [wakeAt] (epoch ms), replacing the alarm before; null takes it away. */
    fun set(wakeAt: Long?) {
        val intent = PendingIntent.getBroadcast(
            context, 0, Intent(context, ScheduledSendReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        if (wakeAt == null) alarms.cancel(intent)
        else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, wakeAt, intent)
    }
}

/** Sends the planned messages that are due when [ScheduledSendAlarm] rings. It may start the process. */
class ScheduledSendReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as? ChatterApp)?.container ?: return
        // Connecting and sending outlast onReceive.
        val pending = goAsync()
        container.scope.launch {
            try {
                container.scheduled.wakeUp()
                container.scheduled.sendDue()
            } finally {
                pending.finish()
            }
        }
    }
}

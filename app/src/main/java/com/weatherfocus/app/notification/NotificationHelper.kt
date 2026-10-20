package com.weatherfocus.app.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.weatherfocus.app.MainActivity
import com.weatherfocus.app.R
import com.weatherfocus.app.data.model.CustomAlertMatch
import com.weatherfocus.app.data.model.DoNotDisturbSettings
import java.util.Calendar

object NotificationHelper {
    private const val CHANNEL_ID = "weather_alerts"
    private const val NOTIFICATION_ID_BASE = 1000

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Weather alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Custom weather alert rules you configured in Settings"
            }
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    /** Returns true if the given Do-Not-Disturb window is currently active and notifications should be suppressed. */
    fun isQuietHoursActive(dnd: DoNotDisturbSettings, nowMillis: Long = System.currentTimeMillis()): Boolean {
        if (!dnd.enabled) return false
        val hour = Calendar.getInstance().apply { timeInMillis = nowMillis }.get(Calendar.HOUR_OF_DAY)
        return dnd.isActiveAt(hour)
    }

    /** Shows a weather-alert notification unless the user's configured Do Not Disturb window is currently active. */
    fun showAlert(context: Context, match: CustomAlertMatch, dnd: DoNotDisturbSettings) {
        if (isQuietHoursActive(dnd)) return
        showNow(context, match)
    }

    /** Fires immediately, ignoring Do Not Disturb - used by the "Send test notification" button in Settings so the
     * user can verify notifications are actually reaching their device (permission granted, channel not muted, etc). */
    fun showTestNotification(context: Context) {
        showNow(
            context,
            CustomAlertMatch(
                label = "Test notification",
                detail = "If you can see this, weather alerts will reach you too."
            )
        )
    }

    private fun showNow(context: Context, match: CustomAlertMatch) {
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_alert)
            .setContentTitle(match.label)
            .setContentText(match.detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(match.detail))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        // Distinct notification ID per alert type so multiple simultaneous alerts each get their own tray entry.
        val notificationId = NOTIFICATION_ID_BASE + match.type.ordinal
        runCatching { NotificationManagerCompat.from(context).notify(notificationId, notification) }
    }
}

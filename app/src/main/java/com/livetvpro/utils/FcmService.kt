package com.livetvpro.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.livetvpro.R
import com.livetvpro.MainActivity

class FcmService : FirebaseMessagingService() {

    companion object {
        private const val CHANNEL_ID_URGENT = "fcm_urgent_channel"
        private const val CHANNEL_ID_HIGH = "fcm_high_channel"
        private const val CHANNEL_ID_DEFAULT = "fcm_default_channel"
        private const val CHANNEL_ID_LOW = "fcm_low_channel"
        private const val CHANNEL_ID_NONE = "fcm_none_channel"
        private const val NOTIFICATION_ID = 2001
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        val title = message.notification?.title
            ?: message.data["title"]
            ?: getString(R.string.app_name)

        val body = message.notification?.body
            ?: message.data["body"]
            ?: return

        val url = message.data["url"]
        val priority = message.data["priority"]

        showNotification(title, body, url, priority)
    }

    private fun createNotificationChannels(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID_URGENT, "Urgent", NotificationManager.IMPORTANCE_HIGH)
            )
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID_HIGH, "High", NotificationManager.IMPORTANCE_HIGH).apply {
                    setSound(null, null)
                }
            )
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID_DEFAULT, "General", NotificationManager.IMPORTANCE_DEFAULT)
            )
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID_LOW, "Silent", NotificationManager.IMPORTANCE_LOW)
            )
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID_NONE, "Blocked", NotificationManager.IMPORTANCE_NONE)
            )
        }
    }

    private fun showNotification(title: String, body: String, url: String?, priority: String?) {
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        createNotificationChannels(notificationManager)

        val channelId = when (priority) {
            "urgent" -> CHANNEL_ID_URGENT
            "high" -> CHANNEL_ID_HIGH
            "low" -> CHANNEL_ID_LOW
            "silent" -> CHANNEL_ID_NONE
            else -> CHANNEL_ID_DEFAULT
        }

        val intent = if (!url.isNullOrBlank()) {
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
        } else {
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_play)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}

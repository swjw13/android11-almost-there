package com.woory.presentation.background.notification

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.woory.almostthere.background.notification.NotificationChannelProvider
import com.woory.presentation.R
import com.woory.presentation.background.alarm.AlarmTouchReceiver
import com.woory.presentation.background.util.putPromiseAlarm
import com.woory.presentation.model.PromiseAlarm
import com.woory.presentation.util.PROMISE_ALARM_KEY

object NotificationProvider {
    const val PROMISE_READY_NOTIFICATION_ID = 80
    const val PROMISE_START_NOTIFICATION_ID = 81

    fun createNotificationBuilder(
        context: Context,
        channelId: String,
        title: String,
        content: String,
        priority: Int,
        pendingIntent: PendingIntent,
    ): NotificationCompat.Builder {

        return NotificationCompat.Builder(context, channelId).apply {
            // Todo :: 앱 아이콘 변경 필요
            setSmallIcon(R.drawable.ic_launcher_foreground)
            setContentTitle(title)
            setContentText(content)
            setAutoCancel(true)
            this.priority = priority

            setContentIntent(pendingIntent)
        }
    }

    fun notifyPromiseReadyNotification(
        context: Context,
        title: String,
        content: String,
        promiseAlarm: PromiseAlarm,
    ) {
        val notificationManager = NotificationManagerCompat.from(context)

        val intent = Intent(context, AlarmTouchReceiver::class.java)
        intent.putPromiseAlarm(promiseAlarm)

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            PROMISE_READY_NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = createNotificationBuilder(
            context,
            NotificationChannelProvider.PROMISE_READY_CHANNEL_ID,
            title,
            content,
            NotificationCompat.PRIORITY_HIGH,
            pendingIntent,
        )
        notificationManager.notify(PROMISE_READY_NOTIFICATION_ID, notification.build())
    }

    fun notifyPromiseStartNotification(
        context: Context,
        title: String,
        content: String,
        promiseAlarm: PromiseAlarm,
    ) {
        val notificationManager = NotificationManagerCompat.from(context)

        val intent = Intent(context, AlarmTouchReceiver::class.java).apply {
            putPromiseAlarm(promiseAlarm)
        }

        val pendingIntent = PendingIntent.getBroadcast(context, PROMISE_START_NOTIFICATION_ID, intent, PendingIntent.FLAG_IMMUTABLE)

        val notification = createNotificationBuilder(
            context,
            NotificationChannelProvider.PROMISE_START_CHANNEL_ID,
            title,
            content,
            NotificationCompat.PRIORITY_HIGH,
            pendingIntent,
        )
        notificationManager.notify(PROMISE_START_NOTIFICATION_ID, notification.build())
    }
}
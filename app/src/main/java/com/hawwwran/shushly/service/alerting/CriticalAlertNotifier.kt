package com.hawwwran.shushly.service.alerting

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.hawwwran.shushly.MainActivity
import com.hawwwran.shushly.R
import com.hawwwran.shushly.core.model.CriticalAlert
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Posts Shushly's own audible re-alert (spec §12). */
interface CriticalAlertNotifier {
    /** Posts under [notifId]; the pipeline derives it per source notification so distinct
     * important notifications each sound, while re-posting the same source updates silently. */
    fun post(alert: CriticalAlert, notifId: Int)

    /** Cancels a previously posted alert (used to mirror source-notification dismissal). */
    fun cancel(notifId: Int)
}

@Singleton
class CriticalAlertNotifierImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : CriticalAlertNotifier {

    private val nm: NotificationManager =
        context.getSystemService(NotificationManager::class.java)

    override fun post(alert: CriticalAlert, notifId: Int) {
        NotificationChannels.ensureAll(context)
        // Vibration is fixed per channel (immutable after creation), so the toggle selects which.
        val channelId = NotificationChannels.channelForVibrate(alert.vibrate)

        val publicVersion = Notification.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_shushly_alert)
            .setContentTitle("Shushly")
            .setContentText("Critical update")
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()

        val contentIntent = alert.sourceContentIntent ?: PendingIntent.getActivity(
            context,
            notifId,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = Notification.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_shushly_alert)
            .setContentTitle("Shushly • Critical update")
            .setContentText(alert.titleLine)
            .setStyle(Notification.BigTextStyle().bigText("${alert.titleLine}\n${alert.reasonLine}"))
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setContentIntent(contentIntent)
            // Deliberately NO setFullScreenIntent: prohibited by spec §4.5.
            .build()

        nm.notify(notifId, notification)
    }

    override fun cancel(notifId: Int) {
        nm.cancel(notifId)
    }
}

package com.glooboo.umessage.helpers

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat
import com.glooboo.umessage.R
import com.glooboo.umessage.activities.MainActivity
import com.glooboo.umessage.ui.theme.currentColorScheme

private const val MMS_DOWNLOAD_CHANNEL_ID = "umessage_mms_download"

/**
 * I10: a picture message whose download failed (no mobile data, a carrier error) used to show only a toast, which is
 * gone before anyone looks. This notification stays, says what happened and opens the app. One at a time: a second
 * failure replaces it. The text never names the sender.
 */
fun Context.showMmsDownloadFailedNotification() {
    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    NotificationChannel(
        MMS_DOWNLOAD_CHANNEL_ID,
        getString(R.string.mms_download_failed_channel),
        NotificationManager.IMPORTANCE_DEFAULT,
    ).also { manager.createNotificationChannel(it) }
    val open = PendingIntent.getActivity(
        this,
        NotificationIds.mmsDownloadFailed(0L),
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val text = getString(R.string.mms_download_failed_text)
    val notification = NotificationCompat.Builder(this, MMS_DOWNLOAD_CHANNEL_ID)
        .setContentTitle(getString(R.string.mms_download_failed_title))
        .setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setColor(currentColorScheme(this).primary.toArgb())
        .setSmallIcon(R.drawable.ic_messenger)
        .setContentIntent(open)
        .setCategory(Notification.CATEGORY_MESSAGE)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .setAutoCancel(true)
        .build()
    manager.notify(NotificationIds.mmsDownloadFailed(0L), notification)
}

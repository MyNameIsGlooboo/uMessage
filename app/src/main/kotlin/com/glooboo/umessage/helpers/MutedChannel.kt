package com.glooboo.umessage.helpers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.glooboo.umessage.R
import org.fossify.commons.extensions.notificationManager

/**
 * N05: the notification channel of muted conversations. Android has no "quiet" flag for a single notification, so a
 * muted chat's notification (a verification code's "Copy code" included) goes to this low-importance channel: no
 * sound, no vibration, no pop-up.
 */
object MutedChannel {
    const val ID = "muted_conversations"

    /** Creates the channel when it is not there yet and returns its id. */
    fun ensure(context: Context): String {
        NotificationChannel(ID, context.getString(R.string.mute_channel), NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null)
            enableVibration(false)
            context.notificationManager.createNotificationChannel(this)
        }
        return ID
    }
}

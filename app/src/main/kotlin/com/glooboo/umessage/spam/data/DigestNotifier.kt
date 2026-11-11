package com.glooboo.umessage.spam.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.glooboo.umessage.R
import com.glooboo.umessage.helpers.cleanupPrefs
import com.glooboo.umessage.ui.spam.BlockedMessagesActivity

/**
 * I08: posts the weekly summary of what was blocked, when it is due and anything was. One quiet notification on its
 * own low-importance channel; on the lock screen it shows only its title, no counts. Tapping it opens the Blocked tab.
 */
object DigestNotifier {
    const val CHANNEL_ID = "umessage_weekly_digest"
    private const val TAG = "umessage_weekly_digest"
    private const val NOTIFICATION_ID = 0x5B11
    private const val REQUEST_OPEN_BLOCKED = 0x5B11

    /** Posts the digest when the switch is on, a week has passed and something was held. True when it was posted. */
    fun postIfDue(context: Context, now: Long): Boolean {
        val prefs = context.cleanupPrefs
        if (!prefs.digestOn || !prefs.claimDigest(now)) return false
        val blocked = SpamDatabase.getInstance(context).spamDao().getBlockedBetween(now - WeeklyDigest.WEEK_MS, now)
        val digest = WeeklyDigest.compute(blocked, now)
        if (digest.held == 0) return false
        show(context, digest)
        return true
    }

    fun show(context: Context, digest: WeeklyDigest) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.digest_channel),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
        val open = Intent(context, BlockedMessagesActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val contentIntent = PendingIntent.getActivity(
            context, REQUEST_OPEN_BLOCKED, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val title = context.getString(R.string.digest_title)
        val text = text(context, digest)
        val publicVersion = builder(context, title, contentIntent).build()
        val notification = builder(context, title, contentIntent)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .build()
        manager.notify(TAG, NOTIFICATION_ID, notification)
    }

    private fun text(context: Context, digest: WeeklyDigest): String =
        if (digest.important == 0) {
            context.resources.getQuantityString(R.plurals.digest_held, digest.held, digest.held)
        } else {
            context.resources.getQuantityString(
                R.plurals.digest_held_important, digest.held, digest.held, digest.important
            )
        }

    private fun builder(context: Context, title: String, contentIntent: PendingIntent) =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_messenger)
            .setContentTitle(title)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
}

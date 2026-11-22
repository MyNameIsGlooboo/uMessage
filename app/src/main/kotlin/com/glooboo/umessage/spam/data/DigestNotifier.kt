package com.glooboo.umessage.spam.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationCompat
import com.glooboo.umessage.R
import com.glooboo.umessage.databases.MessagesDatabase
import com.glooboo.umessage.helpers.CleanupPrefs
import com.glooboo.umessage.helpers.MainTabLinks
import com.glooboo.umessage.helpers.cleanupPrefs
import com.glooboo.umessage.interfaces.SpamDigestRow
import com.glooboo.umessage.spam.FirstRun

/**
 * I08: posts the weekly summary of what was blocked, when it is due and anything was. One quiet notification on its
 * own low-importance channel; on the lock screen it shows only its title, no counts. Tapping it opens the Blocked tab.
 *
 * Provider truth (section 5): it counts the received texts of the chats in Spam (from the message cache), waits until
 * the first sort is published, like the other daily jobs (3.5), and opens the Spam tab of the main screen.
 */
object DigestNotifier {
    const val CHANNEL_ID = "umessage_weekly_digest"
    private const val TAG = "umessage_weekly_digest"
    private const val NOTIFICATION_ID = 0x5B11
    private const val REQUEST_OPEN_BLOCKED = 0x5B11
    private const val MILLIS = 1000L
    private const val NOT_DUE_PAUSE_MS = 60L * 60 * 1000

    /**
     * H-20: an MMS is dated by its own date, and its download can come long after it (no data, roaming) and only
     * then put its chat in Spam. So the newest MMS of a chat dated before its chat entered Spam (J4-4: the only one
     * whose download can have done that) counts when it is dated up to this long before, plus
     * [WeeklyDigest.ARRIVAL_GRACE_MS]: 24 h 10 min in all. Spam MMS are stored read, so the read flag cannot tell.
     */
    internal const val MMS_DOWNLOAD_GRACE_MS = 24L * 60 * 60 * 1000

    /**
     * H-9: until when the Spam count is not read again after a check found the week not up yet; the daily checks run
     * after every received text, so this keeps the query to about one an hour.
     */
    @Volatile
    private var notDueUntil = 0L

    /** Test seam: the one Spam count read ([spamTexts]); a test makes it fail or counts its calls. */
    @VisibleForTesting
    @Volatile
    internal var readSpamTexts: (Context, Long) -> List<WeeklyDigest.SpamText> =
        { context, now -> spamTexts(context, now) }

    /** Tests: forgets [notDueUntil]. */
    @VisibleForTesting
    internal fun resetDueGuard() {
        notDueUntil = 0L
    }

    /** Posts the digest when the switch is on, a week has passed and something was held. True when it was posted. */
    fun postIfDue(context: Context, now: Long): Boolean {
        val prefs = context.cleanupPrefs
        if (!prefs.digestOn || !sortAllows(context)) return false
        val digest = spamDigest(context, prefs, now) ?: return false
        if (digest.held == 0) return false
        show(context, digest)
        return true
    }

    /**
     * The digest when the week is up (and claimed); null when it is not, or the count failed. Provider truth (H-9):
     * counted before the week is claimed, so a failed read does not cost that week's digest; a check that finds the
     * week not up pauses the count for [NOT_DUE_PAUSE_MS].
     */
    private fun spamDigest(context: Context, prefs: CleanupPrefs, now: Long): WeeklyDigest? {
        if (now in 0 until notDueUntil) return null
        val texts = runCatching { readSpamTexts(context, now) }.getOrNull() ?: return null
        if (prefs.claimDigest(now)) return WeeklyDigest.computeTexts(texts, now)
        notDueUntil = now + NOT_DUE_PAUSE_MS
        return null
    }

    private fun sortAllows(context: Context): Boolean =
        runCatching { FirstRun.state(context).value.allowsFullSort }.getOrDefault(false)

    /**
     * Provider truth: the received texts of the last week in the chats now in Spam, each with the time its chat
     * entered Spam, in one query (H-9). Blocking; throws when the cache cannot be read.
     */
    internal fun spamTexts(context: Context, now: Long): List<WeeklyDigest.SpamText> {
        val from = ((now - WeeklyDigest.WEEK_MS) / MILLIS).toInt()
        return MessagesDatabase.getInstance(context.applicationContext).MessagesDao().getSpamDigestRows(from)
            .map { WeeklyDigest.SpamText(it.sender, it.body, it.date * MILLIS, sinceFor(it)) }
    }

    /** When [row] counts from: its chat's spam_since, for a late MMS moved back by [MMS_DOWNLOAD_GRACE_MS] (H-20). */
    private fun sinceFor(row: SpamDigestRow): Long =
        if (row.lateMms) (row.spamSince - MMS_DOWNLOAD_GRACE_MS).coerceAtLeast(0L) else row.spamSince

    fun show(context: Context, digest: WeeklyDigest) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.digest_channel),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
        val open = MainTabLinks.intent(context, MainTabLinks.TAB_BLOCKED).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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

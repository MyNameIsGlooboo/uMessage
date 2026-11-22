package com.glooboo.umessage.spam.data

import android.content.Context
import android.content.SharedPreferences
import android.provider.Telephony
import androidx.annotation.VisibleForTesting
import com.glooboo.umessage.BuildConfig
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.extensions.postReceivedMessageNotification
import com.glooboo.umessage.helpers.NotifyContext
import com.glooboo.umessage.helpers.NotifyLog
import com.glooboo.umessage.helpers.NotifyMode
import com.glooboo.umessage.helpers.NotifyResult
import com.glooboo.umessage.helpers.ProviderTruthFlag
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.FilterDecision
import com.glooboo.umessage.spam.IncomingMessage
import com.glooboo.umessage.spam.UMessage
import org.fossify.commons.extensions.notificationManager
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * N6: messages that are stored and still owe the user a notification. One entry per provider message id in the
 * private prefs file `notify_due`, written right after the provider insert and removed once the post has an outcome.
 * An entry a dead process left behind is posted by [replay]. The entry holds dates and the notify mode, never a
 * sender, a number or message text.
 */
object NotifyDue {
    private const val PREFS = "notify_due"
    private const val HOLD_FILE = "notify_hold_ms"
    private const val MAX_HOLD_MS = 60_000L

    /** Ids this process is posting right now; replay leaves them alone. */
    private val inFlight = ConcurrentHashMap.newKeySet<Long>()
    private val lock = Any()

    /**
     * Called right after the provider insert. [live] = this thread posts next, so replay must leave it alone. Never
     * throws.
     */
    fun mark(
        context: Context,
        messageId: Long,
        date: Long,
        notify: NotifyContext,
        live: Boolean = true,
        now: Long = System.currentTimeMillis(),
    ) {
        if (messageId <= 0L || notify.mode == NotifyMode.NONE) return
        runCatching {
            synchronized(lock) {
                if (live) inFlight.add(messageId)
                // commit(): on disk before the next step, so a death right after still leaves the entry.
                prefs(context).edit().putString(messageId.toString(), DueMarker(date, now, notify).encode()).commit()
            }
        }
    }

    /** The post of [messageId] has an outcome. Removes the marker unless [result] is FAILED. Never throws. */
    fun settle(context: Context, messageId: Long, result: NotifyResult) {
        runCatching {
            synchronized(lock) {
                inFlight.remove(messageId)
                if (result != NotifyResult.FAILED) prefs(context).edit().remove(messageId.toString()).commit()
            }
        }
    }

    /**
     * Posts what is still owed. Blocking, off the main thread. Returns how many markers are still waiting. Never
     * throws.
     */
    @Synchronized
    fun replay(context: Context, now: Long = System.currentTimeMillis()): Int = runCatching {
        prefs(context).all.entries.count { (key, value) -> replayEntry(context, key, value as? String, now) }
    }.getOrDefault(0)

    /** Debug builds only, for the emulator check: waits when the file `files/notify_hold_ms` exists. */
    internal fun debugHold(context: Context) {
        if (!BuildConfig.DEBUG) return
        runCatching {
            val file = File(context.filesDir, HOLD_FILE)
            if (file.exists()) Thread.sleep(file.readText().trim().toLong().coerceIn(0L, MAX_HOLD_MS))
        }
    }

    @VisibleForTesting
    internal fun pendingIds(context: Context): Set<Long> =
        prefs(context).all.keys.mapNotNullTo(HashSet()) { it.toLongOrNull() }

    @VisibleForTesting
    internal fun resetForTest(context: Context) {
        synchronized(lock) {
            inFlight.clear()
            prefs(context).edit().clear().commit()
        }
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Replays one entry. Returns true when it is still waiting afterwards. */
    private fun replayEntry(context: Context, key: String, value: String?, now: Long): Boolean {
        val id = key.toLongOrNull()
        val marker = value?.let { DueMarker.decode(it) }
        if (id == null || marker == null || now - marker.createdAt > PendingSmsPolicy.MAX_AGE_MS) {
            runCatching { synchronized(lock) { prefs(context).edit().remove(key).commit() } }
            NotifyLog.record(null, 0L, NotifyResult.SKIPPED_STALE)
            return false
        }
        // Being posted right now by this process, or settled meanwhile: not ours, and not waiting.
        if (!claim(context, id, key)) return false
        val row = runCatching { DueReplay.readRow(context, id) }.getOrElse {
            // The provider could not be read: keep the marker and try again later.
            inFlight.remove(id)
            return true
        }
        val result = runCatching { DueReplay.outcome(context, id, marker, row) }.getOrDefault(NotifyResult.FAILED)
        settle(context, id, result)
        return result == NotifyResult.FAILED
    }

    private fun claim(context: Context, id: Long, key: String): Boolean = synchronized(lock) {
        if (id in inFlight || !prefs(context).contains(key)) false else inFlight.add(id)
    }
}

/** One "notify due" entry: `1|<date>|<createdAt>|<mode>|<reason or ->`. No content. */
private class DueMarker(val date: Long, val createdAt: Long, val notify: NotifyContext) {
    fun encode(): String =
        listOf(VERSION, date, createdAt, notify.mode.name, notify.reason?.name ?: NO_REASON).joinToString(SEPARATOR)

    companion object {
        private const val VERSION = "1"
        private const val SEPARATOR = "|"
        private const val NO_REASON = "-"
        private const val FIELDS = 5

        // Field positions after the version.
        private const val DATE = 1
        private const val CREATED_AT = 2
        private const val MODE = 3
        private const val REASON = 4

        /** Null when [value] is not an entry of this version. */
        fun decode(value: String): DueMarker? {
            val parts = value.split(SEPARATOR).takeIf { it.size == FIELDS && it[0] == VERSION } ?: return null
            val date = parts[DATE].toLongOrNull() ?: return null
            val createdAt = parts[CREATED_AT].toLongOrNull() ?: return null
            val mode = NotifyMode.entries.firstOrNull { it.name == parts[MODE] } ?: return null
            // A reason this version does not know (renamed later) only loses the reason, not the notification.
            val reason = DecisionReason.entries.firstOrNull { it.name == parts[REASON] }
            return DueMarker(date, createdAt, NotifyContext(mode, reason))
        }
    }
}

/** The provider row of a message that is owed a notification. */
private class DueRow(
    val threadId: Long,
    val address: String,
    val body: String,
    val date: Long,
    val read: Int,
    val type: Int,
    val subscriptionId: Int,
)

/** The checks and the post of one replayed entry. */
private object DueReplay {
    private val COLUMNS = arrayOf(
        Telephony.Sms._ID, Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE,
        Telephony.Sms.READ, Telephony.Sms.TYPE, Telephony.Sms.SUBSCRIPTION_ID,
    )

    /** The row of message [id], null when there is none. Throws when the provider cannot be read. */
    fun readRow(context: Context, id: Long): DueRow? =
        context.contentResolver
            .query(Telephony.Sms.CONTENT_URI, COLUMNS, "${Telephony.Sms._ID} = ?", arrayOf("$id"), null)
            ?.use { c ->
                if (!c.moveToFirst()) return@use null
                DueRow(
                    threadId = c.getLong(c.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)),
                    address = c.getString(c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)).orEmpty(),
                    body = c.getString(c.getColumnIndexOrThrow(Telephony.Sms.BODY)).orEmpty(),
                    date = c.getLong(c.getColumnIndexOrThrow(Telephony.Sms.DATE)),
                    read = c.getInt(c.getColumnIndexOrThrow(Telephony.Sms.READ)),
                    type = c.getInt(c.getColumnIndexOrThrow(Telephony.Sms.TYPE)),
                    subscriptionId = c.getInt(c.getColumnIndexOrThrow(Telephony.Sms.SUBSCRIPTION_ID)),
                )
            }

    /** Posts the notification of [row] unless a check says it is not owed any more. */
    fun outcome(context: Context, id: Long, marker: DueMarker, row: DueRow?): NotifyResult {
        // Only the message the marker was written for: same id, still in the inbox, same date.
        if (row == null || row.type != Telephony.Sms.MESSAGE_TYPE_INBOX || row.date != marker.date) {
            return NotifyLog.record(null, row?.threadId ?: 0L, NotifyResult.SKIPPED_STALE)
        }
        val skip = when {
            row.read != 0 -> NotifyResult.SKIPPED_READ
            alreadyShown(context, row.threadId, marker.createdAt) -> NotifyResult.SKIPPED_ACTIVE
            blockedNow(context, row) -> NotifyResult.SKIPPED_BLOCKED
            enteredSpamSince(context, row, marker.createdAt) -> NotifyResult.SKIPPED_SPAM
            else -> null
        }
        if (skip != null) return NotifyLog.record(null, row.threadId, skip)
        return context.postReceivedMessageNotification(
            messageId = id,
            isMms = false,
            address = row.address,
            senderName = context.senderNameFor(row.address),
            body = row.body,
            threadId = row.threadId,
            bitmap = null,
            subscriptionId = row.subscriptionId,
            notify = marker.notify,
        )
    }

    /** The process died after `notify` and before the marker was removed: the chat shows a newer post already. */
    private fun alreadyShown(context: Context, threadId: Long, createdAt: Long): Boolean = runCatching {
        // Untagged only: tagged notices (BlockedNotifier and others) share the id space.
        context.notificationManager.activeNotifications.any {
            it.tag == null && it.id == threadId.hashCode() && it.postTime >= createdAt
        }
    }.getOrDefault(false)

    /**
     * The sender is blocked by a rule now. The pure filter: it writes nothing to the decision log. Any other answer,
     * or an error, posts (fail open: the text is in Messages and unread).
     */
    private fun blockedNow(context: Context, row: DueRow): Boolean {
        val incoming = IncomingMessage(row.address, row.body, row.subscriptionId, isMms = false, receivedAt = row.date)
        val decision = runCatching { UMessage.filter(context).evaluate(incoming) }.getOrNull()
        return (decision as? FilterDecision.Block)?.reason == DecisionReason.BLOCK_RULE
    }

    /**
     * J1, provider truth: the chat entered Spam after the marker was written (a group turned off, or the user moved
     * it), so the text is no longer owed a loud notification. A chat that was in Spam already when the text was sent
     * to Messages (a code, an allowed word, a failed check) still gets it: the sorter removes that row only later.
     * The place is the rule the lists use. No row, or an error, posts (fail open).
     */
    private fun enteredSpamSince(context: Context, row: DueRow, createdAt: Long): Boolean = ProviderTruthFlag.enabled &&
        runCatching {
            val since = context.conversationsDB.getSpamThread(row.threadId)?.spamSince ?: 0L
            since > createdAt && SpamPlacement.inSpamNow(context, row.threadId, row.address)
        }.getOrDefault(false)
}

package com.glooboo.umessage.spam.mms

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony.Mms
import android.provider.Telephony.Sms
import android.provider.Telephony.Threads
import android.telephony.TelephonyManager
import com.glooboo.umessage.databases.MessagesDatabase
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.extensions.getConversations
import com.glooboo.umessage.extensions.insertOrUpdateConversation
import com.glooboo.umessage.extensions.messagesDB
import com.glooboo.umessage.helpers.refreshConversations
import com.glooboo.umessage.helpers.refreshMessages
import com.glooboo.umessage.receivers.MmsSubscriptions
import com.glooboo.umessage.spam.SpamThreads
import com.glooboo.umessage.spam.data.SpamDatabase
import com.glooboo.umessage.spam.data.SpamEventEntity
import com.glooboo.umessage.spam.data.SpamEventType
import com.glooboo.umessage.spam.engine.SpamCause
import com.glooboo.umessage.spam.engine.SpamEngine

/** [MmsSpamStore] on the phone's store and the app's own databases. Every call may throw; the caller fails open. */
@Suppress("TooManyFunctions") // The one implementation of the port; see MmsSpamStore.
class AndroidMmsSpamStore(context: Context) : MmsSpamStore {
    private val app = context.applicationContext

    override fun threadOf(address: String): Long = Threads.getOrCreateThreadId(app, address)

    override fun noteSpamArrival(threadId: Long, address: String, cause: SpamCause, at: Long, raise: Boolean) {
        if (!raise && SpamThreads.isSpamThreadOf(app, threadId, address)) return
        SpamThreads.noteArrival(app, threadId, address, cause, at)
    }

    override fun lowerSpamUnread(threadId: Long) {
        val dao = app.conversationsDB
        MessagesDatabase.getInstance(app).runInTransaction(Runnable {
            val row = dao.getSpamThread(threadId) ?: return@Runnable
            if (row.unread <= 0) return@Runnable
            // The DAO has no "- 1": the row is written again with its count lowered, cause and spam_since kept.
            dao.deleteSpamThread(threadId)
            dao.insertSpamThread(row.copy(unread = row.unread - 1))
        })
    }

    override fun isSpamThreadOf(threadId: Long, address: String): Boolean =
        SpamThreads.isSpamThreadOf(app, threadId, address)

    override fun isListedInSpam(threadId: Long): Boolean = app.conversationsDB.isInSpam(threadId)

    override fun setThread(row: Uri, threadId: Long?, silent: Boolean): Boolean {
        val values = ContentValues().apply {
            threadId?.let { put(Mms.THREAD_ID, it) }
            // E-3: both ways, so a notice quieted earlier is unread again when it ends in Messages.
            putSilent(silent)
        }
        return app.contentResolver.update(row, values, null, null) > 0
    }

    override fun noticeOf(row: Uri): NoticeRow? {
        val id = row.lastPathSegment?.toLongOrNull() ?: return null
        val (threadId, read) = app.contentResolver.query(row, arrayOf(Mms.THREAD_ID, Mms.READ), null, null, null)?.use {
            if (it.moveToFirst()) it.getLong(0) to (it.getInt(1) == 1) else null
        } ?: return null
        return NoticeRow(threadId, MmsSubscriptions.senderOf(app, id).orEmpty(), read)
    }

    override fun hasMessages(threadId: Long): Boolean? = runCatching {
        listOf(Sms.CONTENT_URI, Mms.CONTENT_URI).any { uri ->
            app.contentResolver.query(uri, arrayOf(Sms._ID), "${Sms.THREAD_ID} = ?", arrayOf(threadId.toString()), null)
                ?.use { it.count > 0 } ?: error("no cursor")
        }
    }.getOrNull()

    override fun hasScheduled(threadId: Long): Boolean =
        app.messagesDB.getScheduledThreadMessages(threadId).isNotEmpty()

    override fun forgetEmptyChat(threadId: Long) {
        app.conversationsDB.deleteThreadId(threadId)
        app.conversationsDB.deleteSpamThread(threadId)
    }

    override fun showChat(threadId: Long) {
        // E-21: also a number on Android's block list, which the lists leave out until the first sort is published.
        app.getConversations(threadId, includeBlocked = true).firstOrNull()?.let { app.insertOrUpdateConversation(it) }
        refreshMessages()
        refreshConversations(threadId)
    }

    override fun recordHit(ruleId: Long?, inSpam: Boolean) {
        ruleId?.let { SpamEngine.repository(app).recordWordRuleHit(it) }
        if (inSpam) {
            val event = SpamEventEntity(type = SpamEventType.BLOCKED, timestamp = System.currentTimeMillis())
            SpamDatabase.getInstance(app).spamDao().insertEvent(event)
        }
    }

    override fun markNotice(row: Uri, mark: NoticeMark?) {
        val id = row.lastPathSegment?.toLongOrNull() ?: return
        synchronized(MARKS_LOCK) {
            val prefs = app.getSharedPreferences(MARKS_PREFS, Context.MODE_PRIVATE)
            val kept = prefs.getStringSet(MARKS_KEY, emptySet()).orEmpty()
                .filterNot { it.substringBefore('=') == "$id" }
            val value = when (mark) {
                null -> null
                NoticeMark.Skipped -> "$id=$SKIPPED"
                is NoticeMark.Counted -> "$id=${mark.threadId}"
                is NoticeMark.Failed -> "$id=$FAILED${mark.times}"
            }
            // Bounded: the oldest notice rows (lowest ids) go first; a mark only matters while its notice exists.
            val all = (kept + listOfNotNull(value)).sortedBy { it.substringBefore('=').toLongOrNull() ?: 0L }
            prefs.edit().putStringSet(MARKS_KEY, all.takeLast(MAX_MARKS).toSet()).apply()
        }
    }

    override fun markOf(row: Uri): NoticeMark? {
        val id = row.lastPathSegment?.toLongOrNull() ?: return null
        val entry = app.getSharedPreferences(MARKS_PREFS, Context.MODE_PRIVATE)
            .getStringSet(MARKS_KEY, emptySet()).orEmpty()
            .firstOrNull { it.substringBefore('=') == "$id" } ?: return null
        val value = entry.substringAfter('=')
        return when {
            value == SKIPPED -> NoticeMark.Skipped
            value.startsWith(FAILED) -> value.removePrefix(FAILED).toIntOrNull()?.let { NoticeMark.Failed(it) }
            else -> value.toLongOrNull()?.let { NoticeMark.Counted(it) }
        }
    }

    private companion object {
        /** E-22: the explicit marks of notice rows ("id=threadId", "id=skip" or "id=failN"). */
        const val MARKS_PREFS = "umessage_mms_notice_marks"
        const val MARKS_KEY = "marks"
        const val SKIPPED = "skip"
        const val FAILED = "fail"
        const val MAX_MARKS = 200
        val MARKS_LOCK = Any()
    }
}

/** Read and seen ([silent]), or unread and not seen. */
private fun ContentValues.putSilent(silent: Boolean) {
    val flag = if (silent) 1 else 0
    put(Mms.READ, flag)
    put(Mms.SEEN, flag)
}

/** Roaming on the SIM [subscriptionId]; false when it cannot be told (a download then goes ahead). */
internal fun isRoamingOn(context: Context, subscriptionId: Int): Boolean = runCatching {
    val manager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    val forSim = if (subscriptionId >= 0) manager.createForSubscriptionId(subscriptionId) else manager
    forSim.isNetworkRoaming
}.getOrDefault(false)

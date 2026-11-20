package com.glooboo.umessage.spam.mms

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony.Mms
import android.provider.Telephony.Sms
import android.provider.Telephony.Threads
import android.telephony.TelephonyManager
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.receivers.MmsSubscriptions
import com.glooboo.umessage.spam.SpamThreads
import com.glooboo.umessage.spam.data.SpamDatabase
import com.glooboo.umessage.spam.data.SpamEventEntity
import com.glooboo.umessage.spam.data.SpamEventType
import com.glooboo.umessage.spam.engine.SpamCause
import com.glooboo.umessage.spam.engine.SpamEngine

/** [MmsSpamStore] on the phone's store and the app's own databases. Every call may throw; the caller fails open. */
class AndroidMmsSpamStore(context: Context) : MmsSpamStore {
    private val app = context.applicationContext

    override fun threadOf(address: String): Long = Threads.getOrCreateThreadId(app, address)

    override fun noteSpamArrival(threadId: Long, address: String, cause: SpamCause, at: Long) =
        SpamThreads.noteArrival(app, threadId, address, cause, at)

    override fun isSpamThreadOf(threadId: Long, address: String): Boolean =
        SpamThreads.isSpamThreadOf(app, threadId, address)

    override fun setThread(row: Uri, threadId: Long, silent: Boolean): Boolean {
        val values = ContentValues().apply {
            put(Mms.THREAD_ID, threadId)
            if (silent) putSilent(true)
        }
        return app.contentResolver.update(row, values, null, null) > 0
    }

    override fun setSilent(row: Uri, silent: Boolean): Boolean =
        app.contentResolver.update(row, ContentValues().apply { putSilent(silent) }, null, null) > 0

    override fun noticeOf(row: Uri): NoticeRow? {
        val id = row.lastPathSegment?.toLongOrNull() ?: return null
        val threadId = app.contentResolver.query(row, arrayOf(Mms.THREAD_ID), null, null, null)?.use {
            if (it.moveToFirst()) it.getLong(0) else null
        } ?: return null
        return NoticeRow(threadId, MmsSubscriptions.senderOf(app, id).orEmpty())
    }

    override fun hasMessages(threadId: Long): Boolean? = runCatching {
        listOf(Sms.CONTENT_URI, Mms.CONTENT_URI).any { uri ->
            app.contentResolver.query(uri, arrayOf(Sms._ID), "${Sms.THREAD_ID} = ?", arrayOf(threadId.toString()), null)
                ?.use { it.count > 0 } ?: error("no cursor")
        }
    }.getOrNull()

    override fun forgetEmptyChat(threadId: Long) {
        app.conversationsDB.deleteThreadId(threadId)
        app.conversationsDB.deleteSpamThread(threadId)
    }

    override fun recordRuleHit(ruleId: Long) {
        SpamEngine.repository(app).recordWordRuleHit(ruleId)
    }

    override fun recordSpamEvent() {
        val event = SpamEventEntity(type = SpamEventType.BLOCKED, timestamp = System.currentTimeMillis())
        SpamDatabase.getInstance(app).spamDao().insertEvent(event)
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

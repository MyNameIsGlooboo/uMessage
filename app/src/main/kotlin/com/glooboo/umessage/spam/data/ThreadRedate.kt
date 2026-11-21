package com.glooboo.umessage.spam.data

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.provider.Telephony.Mms
import android.provider.Telephony.Sms
import android.provider.Telephony.Threads
import android.util.Log

/**
 * Dates a thread in the phone's store by its newest message, so every SMS app sees the real date.
 *
 * Every insert, and an MMS date update, makes the provider's triggers stamp the thread with "now". The provider's own
 * recompute (an always-false delete on `conversations/<id>`) fixes that, but on API 35 it only visits threads that
 * hold an SMS (its updateThreads picks threads by `EXISTS (SELECT _id FROM sms ...)`), so an MMS-only thread kept
 * the time of the migration or restore (emulator run 1: 0 of 40 migrated MMS threads right). For such a thread a
 * blank draft dated 0 gives the recompute an SMS to find; the draft can never win the date, counts as no message and
 * is deleted at once, and that delete leaves the thread alone. A draft left behind by a run that died in between is
 * deleted the next time the thread is dated.
 */
internal object ThreadRedate {
    private const val TAG = "uMessageReceive"
    private const val MILLIS = 1000L
    private const val HELPER_WHERE =
        "${Sms.THREAD_ID} = ? AND ${Sms.TYPE} = ${Sms.MESSAGE_TYPE_DRAFT} AND ${Sms.DATE} = 0 AND " +
            "(${Sms.BODY} IS NULL OR ${Sms.BODY} = '') AND ${Sms.ADDRESS} IS NULL"

    fun redate(resolver: ContentResolver, threadId: Long) {
        runCatching {
            resolver.delete(Sms.CONTENT_URI, HELPER_WHERE, arrayOf("$threadId"))
            recompute(resolver, threadId)
            val newest = newestDate(resolver, threadId)
            val stamped = threadDate(resolver, threadId) ?: 0L
            if (newest > 0 && stamped > newest) redateWithHelper(resolver, threadId)
        }.onFailure { Log.w(TAG, "Thread keeps the time it was written", it) }
    }

    /** The newest message of [threadId] in milliseconds (MMS dates are seconds in the store); 0 when none. */
    fun newestDate(resolver: ContentResolver, threadId: Long): Long {
        val args = arrayOf("$threadId")
        val sms = resolver.query(Sms.CONTENT_URI, arrayOf(Sms.DATE), "${Sms.THREAD_ID} = ?", args, "${Sms.DATE} DESC")
            ?.use { if (it.moveToFirst()) it.getLong(0) else 0L } ?: 0L
        val mms = resolver.query(Mms.CONTENT_URI, arrayOf(Mms.DATE), "${Mms.THREAD_ID} = ?", args, "${Mms.DATE} DESC")
            ?.use { if (it.moveToFirst()) it.getLong(0) * MILLIS else 0L } ?: 0L
        return maxOf(sms, mms)
    }

    private fun redateWithHelper(resolver: ContentResolver, threadId: Long) {
        val values = ContentValues().apply {
            put(Sms.THREAD_ID, threadId)
            put(Sms.TYPE, Sms.MESSAGE_TYPE_DRAFT)
            put(Sms.DATE, 0L)
            put(Sms.BODY, "")
            // The insert trigger reads the thread's unread state from its SMS only: the draft must not count.
            put(Sms.READ, 1)
            put(Sms.SEEN, 1)
        }
        val helper = resolver.insert(Sms.CONTENT_URI, values) ?: return
        try {
            recompute(resolver, threadId)
        } finally {
            resolver.delete(helper, null, null)
        }
        // The draft's insert marked the thread read; an unread MMS in it makes it unread again (the read trigger).
        val unread = ContentValues().apply { put(Mms.READ, 0) }
        resolver.update(Mms.CONTENT_URI, unread, "${Mms.THREAD_ID} = ? AND ${Mms.READ} = 0", arrayOf("$threadId"))
    }

    private fun recompute(resolver: ContentResolver, threadId: Long) {
        resolver.delete(ContentUris.withAppendedId(Threads.CONTENT_URI, threadId), "1 = 0", null)
    }

    private fun threadDate(resolver: ContentResolver, threadId: Long): Long? {
        val uri = Threads.CONTENT_URI.buildUpon().appendQueryParameter("simple", "true").build()
        return resolver.query(uri, arrayOf(Threads.DATE), "${Threads._ID} = ?", arrayOf("$threadId"), null)
            ?.use { if (it.moveToFirst()) it.getLong(0) else null }
    }
}

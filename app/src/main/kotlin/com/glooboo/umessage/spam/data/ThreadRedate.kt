package com.glooboo.umessage.spam.data

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
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
 * the time of the migration or restore (emulator run 1: 0 of 40 migrated MMS threads right).
 *
 * For such a thread, and only when it holds no SMS row at all, a blank draft dated 0 gives the recompute an SMS to
 * find; the draft can never win the date, counts as no message and is deleted at once, and that delete leaves the
 * thread alone. Only then: the provider deletes a thread's other drafts on every draft insert, and a draft typed in
 * another app must never go (T-1). Where the recompute already handles MMS-only threads, no draft is ever written.
 * The recompute with the draft also sets the thread's `error` column from its SMS, so an MMS-only thread whose
 * only other content is a failed sent MMS loses that mark; the stock provider does the same to any thread on any SMS
 * delete, and uMessage does not read it (T-3). A draft left behind (a kill, a failed delete) is uMessage's own row
 * ([Sms.CREATOR]): the next dating of its thread deletes it, and [sweep] dates every thread that still holds one.
 */
internal object ThreadRedate {
    private const val TAG = SpamProviderMigration.TAG
    private const val MILLIS = 1000L
    private const val HELPER =
        "${Sms.TYPE} = ${Sms.MESSAGE_TYPE_DRAFT} AND ${Sms.DATE} = 0 AND (${Sms.BODY} IS NULL OR ${Sms.BODY} = '') " +
            "AND ${Sms.ADDRESS} IS NULL AND ${Sms.CREATOR} = ?"
    private const val HELPER_WHERE = "${Sms.THREAD_ID} = ? AND $HELPER"

    /**
     * Dates [threadId] by its newest message. True when that finished: the thread is right, gone, or cannot be
     * dated without risk (it holds an SMS row); false when a step failed and it must be dated again.
     */
    fun redate(context: Context, threadId: Long): Boolean = runCatching {
        val resolver = context.contentResolver
        val args = arrayOf("$threadId", context.packageName)
        resolver.delete(Sms.CONTENT_URI, HELPER_WHERE, args)
        recompute(resolver, threadId)
        val newest = newestDate(resolver, threadId)
        val stamped = threadDate(resolver, threadId) ?: 0L
        when {
            newest <= 0 || stamped <= newest -> true
            smsRows(resolver, threadId) != 0 -> true
            else -> redateWithHelper(context, threadId)
        }
    }.onFailure { Log.w(TAG, "Thread keeps the time it was written", it) }.getOrDefault(false)

    /**
     * Dates again every thread that still holds a helper draft of uMessage (a run or a "Not spam" that died between
     * its writes). Run start and app start, off the main thread. True when none is left.
     */
    fun sweep(context: Context): Boolean = runCatching {
        val threads = context.contentResolver.query(
            Sms.CONTENT_URI, arrayOf(Sms.THREAD_ID), HELPER, arrayOf(context.packageName), null,
        )?.use { c -> buildSet<Long> { while (c.moveToNext()) add(c.getLong(0)) } } ?: return@runCatching false
        threads.map { redate(context, it) }.all { it }
    }.onFailure { Log.w(TAG, "Helper drafts not checked", it) }.getOrDefault(false)

    /** The newest message of [threadId] in milliseconds (MMS dates are seconds in the store); 0 when none. */
    fun newestDate(resolver: ContentResolver, threadId: Long): Long {
        val args = arrayOf("$threadId")
        val sms = resolver.query(Sms.CONTENT_URI, arrayOf(Sms.DATE), "${Sms.THREAD_ID} = ?", args, "${Sms.DATE} DESC")
            ?.use { if (it.moveToFirst()) it.getLong(0) else 0L } ?: 0L
        val mms = resolver.query(Mms.CONTENT_URI, arrayOf(Mms.DATE), "${Mms.THREAD_ID} = ?", args, "${Mms.DATE} DESC")
            ?.use { if (it.moveToFirst()) it.getLong(0) * MILLIS else 0L } ?: 0L
        return maxOf(sms, mms)
    }

    /**
     * Writes the helper, recomputes, puts back the unread state and deletes the helper. A step that fails leaves the
     * helper for the next dating of the thread ([redate] deletes it first, [sweep] finds it): false.
     */
    private fun redateWithHelper(context: Context, threadId: Long): Boolean {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(Sms.THREAD_ID, threadId)
            put(Sms.TYPE, Sms.MESSAGE_TYPE_DRAFT)
            put(Sms.DATE, 0L)
            put(Sms.BODY, "")
            // The insert trigger reads the thread's unread state from its SMS only: the draft must not count.
            put(Sms.READ, 1)
            put(Sms.SEEN, 1)
        }
        // Refused (not the default app): nothing was written, and the date stays as the provider left it.
        val helper = resolver.insert(Sms.CONTENT_URI, values) ?: return true
        recompute(resolver, threadId)
        // The draft's insert marked the thread read; an unread MMS in it makes it unread again (the read trigger).
        val unread = ContentValues().apply { put(Mms.READ, 0) }
        resolver.update(Mms.CONTENT_URI, unread, "${Mms.THREAD_ID} = ? AND ${Mms.READ} = 0", arrayOf("$threadId"))
        return resolver.delete(helper, null, null) == 1
    }

    /** Every SMS row of [threadId], drafts included; -1 when the store could not be asked. */
    private fun smsRows(resolver: ContentResolver, threadId: Long): Int =
        resolver.query(Sms.CONTENT_URI, arrayOf(Sms._ID), "${Sms.THREAD_ID} = ?", arrayOf("$threadId"), null)
            ?.use { it.count } ?: -1

    private fun recompute(resolver: ContentResolver, threadId: Long) {
        resolver.delete(ContentUris.withAppendedId(Threads.CONTENT_URI, threadId), "1 = 0", null)
    }

    private fun threadDate(resolver: ContentResolver, threadId: Long): Long? {
        val uri = Threads.CONTENT_URI.buildUpon().appendQueryParameter("simple", "true").build()
        return resolver.query(uri, arrayOf(Threads.DATE), "${Threads._ID} = ?", arrayOf("$threadId"), null)
            ?.use { if (it.moveToFirst()) it.getLong(0) else null }
    }
}

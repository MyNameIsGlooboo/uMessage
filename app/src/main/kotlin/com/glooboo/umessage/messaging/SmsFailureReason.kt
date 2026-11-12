package com.glooboo.umessage.messaging

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony.Sms
import android.telephony.SmsManager
import com.glooboo.umessage.R

/**
 * Thread-27: why a text failed, shown under the "Not sent" row.
 *
 * The phone's send report ([SmsManager] result code) is kept in the provider's own `error_code` column of the failed
 * row, so no table of this app changes. 0 means "no reason stored" (every real failure code is 1 or more).
 */
object SmsFailureReason {

    /** The provider's column for a message's error code (the same string as `Telephony.Sms.ERROR_CODE`). */
    internal const val COLUMN = "error_code"

    /** No reason stored: the message did not fail, or failed before the reason was kept. */
    const val NONE = 0

    /** Writes [resultCode] (a failed send report) on the provider row [uri]. Never throws: the reason is a nicety. */
    fun record(context: Context, uri: Uri?, resultCode: Int) {
        if (uri == null || resultCode <= NONE) return
        runCatching {
            context.contentResolver.update(uri, ContentValues().apply { put(COLUMN, resultCode) }, null, null)
        }
    }

    /** Forgets the reason of [uri]: the message went out after all. */
    fun clear(context: Context, uri: Uri?) {
        if (uri == null) return
        runCatching {
            context.contentResolver.update(uri, ContentValues().apply { put(COLUMN, NONE) }, null, null)
        }
    }

    /**
     * The stored reason of each failed text of [threadId], by message id; rows without a reason are left out. One
     * provider query: call it off the main thread.
     */
    fun failedCodes(context: Context, threadId: Long): Map<Long, Int> {
        if (threadId <= 0L) return emptyMap()
        return runCatching {
            val codes = HashMap<Long, Int>()
            context.contentResolver.query(
                Sms.CONTENT_URI,
                arrayOf(Sms._ID, COLUMN),
                "${Sms.THREAD_ID} = ? AND ${Sms.TYPE} = ?",
                arrayOf(threadId.toString(), Sms.MESSAGE_TYPE_FAILED.toString()),
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val code = cursor.getInt(1)
                    if (code > NONE) codes[cursor.getLong(0)] = code
                }
            }
            codes
        }.getOrDefault(emptyMap())
    }

    /**
     * The words for a stored [code], or null when none is stored. A general failure, and a code that names nothing
     * more specific, read as [R.string.thread_failed_reason_generic].
     */
    fun text(context: Context, code: Int): String? {
        if (code <= NONE) return null
        val res = smsSendingErrorRes(code)
        return if (code == SmsManager.RESULT_ERROR_GENERIC_FAILURE || res == R.string.message_not_sent_short) {
            context.getString(R.string.thread_failed_reason_generic, code)
        } else {
            context.getString(res)
        }
    }
}

package com.glooboo.umessage.messaging

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.Telephony.Mms
import android.provider.Telephony.Sms
import com.glooboo.umessage.extensions.messagesDB
import java.util.concurrent.TimeUnit

/**
 * An SMS that has waited this long for its sent report will not get one: the radio answers within seconds, even when
 * there is no service, so the report was lost (for example the phone restarted mid-send).
 */
val STALE_OUTBOX_AGE_MILLIS = TimeUnit.MINUTES.toMillis(10)

/**
 * Marks SMS rows that have been stuck as "sending" for longer than [maxAgeMillis] as failed, in the telephony
 * provider and in the app's own database, so the thread shows them as not sent and offers a resend instead of
 * "sending" forever. A sent report that still arrives later sets the right state again. Returns how many rows
 * were marked. Picture messages that never left the outbox are failed the same way (MS-lowB, B19).
 */
fun Context.failStaleOutboxMessages(
    now: Long = System.currentTimeMillis(),
    maxAgeMillis: Long = STALE_OUTBOX_AGE_MILLIS
): Int {
    val cutoff = now - maxAgeMillis
    val mmsMarked = failStaleOutboxMms(cutoff)
    val ids = ArrayList<Long>()
    try {
        contentResolver.query(
            Sms.CONTENT_URI,
            arrayOf(Sms._ID),
            "${Sms.TYPE} = ? AND ${Sms.DATE} < ?",
            arrayOf(Sms.MESSAGE_TYPE_OUTBOX.toString(), cutoff.toString()),
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                ids.add(cursor.getLong(0))
            }
        }
    } catch (_: Exception) {
        return mmsMarked
    }

    var marked = mmsMarked
    val values = ContentValues().apply { put(Sms.TYPE, Sms.MESSAGE_TYPE_FAILED) }
    for (id in ids) {
        try {
            // Only rows still in the outbox: a report that arrived meanwhile wins.
            val updated = contentResolver.update(
                Sms.CONTENT_URI,
                values,
                "${Sms._ID} = ? AND ${Sms.TYPE} = ?",
                arrayOf(id.toString(), Sms.MESSAGE_TYPE_OUTBOX.toString())
            )
            if (updated > 0) {
                marked++
                runCatching { messagesDB.updateType(id, isMMS = false, type = Sms.MESSAGE_TYPE_FAILED) }
                // Its remaining part reports were lost, so the stored progress would stay forever.
                runCatching { SentParts.forget(this, ContentUris.withAppendedId(Sms.CONTENT_URI, id)) }
            }
        } catch (_: Exception) {
        }
    }
    return marked
}

/** The MMS rows still in the outbox since before [cutoffMillis] (a process killed mid-send): marked failed. */
private fun Context.failStaleOutboxMms(cutoffMillis: Long): Int {
    val ids = ArrayList<Long>()
    try {
        contentResolver.query(
            Mms.CONTENT_URI,
            arrayOf(Mms._ID),
            "${Mms.MESSAGE_BOX} = ? AND ${Mms.DATE} < ?",
            // The MMS provider keeps its date in seconds.
            arrayOf(Mms.MESSAGE_BOX_OUTBOX.toString(), (cutoffMillis / 1000).toString()),
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) ids.add(cursor.getLong(0))
        }
    } catch (_: Exception) {
        return 0
    }
    var marked = 0
    val values = ContentValues().apply { put(Mms.MESSAGE_BOX, Mms.MESSAGE_BOX_FAILED) }
    for (id in ids) {
        try {
            val updated = contentResolver.update(
                Mms.CONTENT_URI, values, "${Mms._ID} = ? AND ${Mms.MESSAGE_BOX} = ?",
                arrayOf(id.toString(), Mms.MESSAGE_BOX_OUTBOX.toString())
            )
            if (updated > 0) {
                marked++
                runCatching { messagesDB.updateType(id, isMMS = true, type = Mms.MESSAGE_BOX_FAILED) }
            }
        } catch (_: Exception) {
        }
    }
    return marked
}

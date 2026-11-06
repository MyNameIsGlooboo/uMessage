package com.glooboo.umessage.messaging

import android.content.ContentValues
import android.content.Context
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
 * were marked.
 */
fun Context.failStaleOutboxMessages(
    now: Long = System.currentTimeMillis(),
    maxAgeMillis: Long = STALE_OUTBOX_AGE_MILLIS
): Int {
    val cutoff = now - maxAgeMillis
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
        return 0
    }

    var marked = 0
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
            }
        } catch (_: Exception) {
        }
    }
    return marked
}

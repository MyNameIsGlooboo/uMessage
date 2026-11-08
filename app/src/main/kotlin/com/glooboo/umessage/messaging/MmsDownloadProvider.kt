package com.glooboo.umessage.messaging

import android.content.Context
import android.provider.Telephony.Mms
import android.telephony.SubscriptionManager
import android.util.Log
import com.android.mms.transaction.DownloadManager
import com.google.android.mms.pdu_alt.PduHeaders
import com.glooboo.umessage.models.Message

private const val TAG = "uMessageReceive"
private const val QUERY_CHUNK = 200

/**
 * Of the loaded [messages], the ones that are still only a notification-ind in the system provider (message type
 * 130, no retrieved message), by id, with the carrier's expiry in seconds. Reads the provider; call off the main
 * thread. Empty when nothing looks like one.
 */
fun Context.undownloadedMms(messages: List<Message>): Map<Long, Long> {
    val ids = messages.filter(MmsDownloadRules::isCandidate).map { it.id }
    if (ids.isEmpty()) return emptyMap()
    return runCatching { queryPendingMms(ids).mapValues { it.value.expirySeconds } }
        .onFailure { Log.w(TAG, "Pending MMS lookup failed", it) }
        .getOrDefault(emptyMap())
}

/** The notification-ind rows among [ids]. */
internal fun Context.queryPendingMms(ids: List<Long>): Map<Long, PendingMms> {
    val found = HashMap<Long, PendingMms>()
    val projection = arrayOf(
        Mms._ID, Mms.CONTENT_LOCATION, Mms.TRANSACTION_ID, Mms.EXPIRY, Mms.SUBSCRIPTION_ID,
    )
    ids.chunked(QUERY_CHUNK).forEach { chunk ->
        val selection = "${Mms.MESSAGE_TYPE} = ? AND ${Mms._ID} IN (${chunk.joinToString(",")})"
        val args = arrayOf(PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND.toString())
        contentResolver.query(Mms.CONTENT_URI, projection, selection, args, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                found[id] = PendingMms(
                    id = id,
                    contentLocation = cursor.getString(1).orEmpty(),
                    transactionId = cursor.getString(2).orEmpty(),
                    expirySeconds = cursor.getLong(cursor.getColumnIndexOrThrow(Mms.EXPIRY)),
                    subscriptionId = cursor.getInt(cursor.getColumnIndexOrThrow(Mms.SUBSCRIPTION_ID)),
                )
            }
        }
    }
    return found
}

/**
 * "Tap to download": asks the system to download the picture message [messageId] the way an automatic download does
 * (mmslib's own DownloadManager; the result reaches the same MmsReceiver as any download, which stores the message
 * and drops the notification-ind). Runs the provider and system calls, so call it off the main thread.
 */
fun Context.downloadMms(messageId: Long, tracker: MmsDownloadTracker = MmsDownloadTracker.shared): MmsRetryResult {
    val app = applicationContext
    return retryMmsDownload(
        messageId, tracker, System.currentTimeMillis(),
        lookup = { id -> app.queryPendingMms(listOf(id))[id]?.takeIf { it.contentLocation.isNotEmpty() } },
        start = { pending ->
            val subscription = pending.subscriptionId.takeIf { it > 0 }
                ?: SubscriptionManager.getDefaultSmsSubscriptionId()
            DownloadManager.getInstance().downloadMultimediaMessage(
                app, pending.contentLocation, pending.transactionId, Mms.CONTENT_URI.buildUpon()
                    .appendPath(pending.id.toString()).build(),
                true, subscription,
            )
        },
    )
}

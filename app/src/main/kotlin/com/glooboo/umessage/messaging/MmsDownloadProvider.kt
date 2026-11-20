package com.glooboo.umessage.messaging

import android.content.Context
import android.net.Uri
import android.provider.Telephony.Mms
import android.telephony.SubscriptionManager
import android.util.Log
import com.android.mms.transaction.DownloadManager
import com.google.android.mms.pdu_alt.PduHeaders
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.receivers.MmsPushDownload

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
    // A download started by the push is still running: a second one would save the message twice.
    val running = runCatching { app.queryPendingMms(listOf(messageId))[messageId]?.contentLocation }.getOrNull()
    if (running != null && MmsPushDownload.isStarted(running)) return MmsRetryResult.AlreadyDownloading
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

/** A notice (notification-ind) of the phone's store that has no chat (E-1, E-4). */
internal data class ChatlessNotice(val uri: Uri, val location: String, val subscriptionId: Int)

/**
 * The notices saved before [before] (ms) that have no chat: mmslib's dummy thread, 0 or none. Read off the main
 * thread.
 */
internal fun Context.chatlessNotices(before: Long): List<ChatlessNotice> {
    val found = ArrayList<ChatlessNotice>()
    val selection = "${Mms.MESSAGE_TYPE} = ? AND (${Mms.THREAD_ID} IS NULL OR ${Mms.THREAD_ID} <= 0 OR " +
        "${Mms.THREAD_ID} = ?) AND ${Mms.DATE} < ?"
    val args = arrayOf(
        PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND.toString(), Long.MAX_VALUE.toString(),
        (before / MILLIS_PER_SECOND).toString(),
    )
    val projection = arrayOf(Mms._ID, Mms.CONTENT_LOCATION, Mms.SUBSCRIPTION_ID)
    contentResolver.query(Mms.CONTENT_URI, projection, selection, args, null)?.use { cursor ->
        while (cursor.moveToNext()) {
            val uri = Mms.Inbox.CONTENT_URI.buildUpon().appendPath(cursor.getLong(0).toString()).build()
            found += ChatlessNotice(uri, cursor.getString(1).orEmpty(), cursor.getInt(2))
        }
    }
    return found
}

/**
 * E-17: the MMS the library stored for the download of [transactionId] / [location] although it reported nothing
 * (it stored the row, then a later step threw). Null when there is none.
 */
internal fun Context.storedMmsFor(transactionId: String, location: String): Uri? {
    if (transactionId.isEmpty() && location.isEmpty()) return null
    val selection = "${Mms.MESSAGE_TYPE} = ? AND (${Mms.TRANSACTION_ID} = ? OR ${Mms.CONTENT_LOCATION} = ?)"
    val args = arrayOf(PduHeaders.MESSAGE_TYPE_RETRIEVE_CONF.toString(), transactionId, location)
    return contentResolver.query(Mms.CONTENT_URI, arrayOf(Mms._ID), selection, args, "${Mms._ID} DESC")?.use {
        if (it.moveToFirst()) Mms.CONTENT_URI.buildUpon().appendPath(it.getLong(0).toString()).build() else null
    }
}

private const val MILLIS_PER_SECOND = 1000L

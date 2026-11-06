package com.glooboo.umessage.spam.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony.Mms
import android.provider.Telephony.Sms
import android.util.Log
import com.android.mms.service_alt.DownloadRequest
import com.android.mms.service_alt.MmsConfig
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.extensions.getConversations
import com.glooboo.umessage.extensions.getMMS
import com.glooboo.umessage.extensions.insertOrUpdateConversation
import com.glooboo.umessage.extensions.messagesDB
import com.glooboo.umessage.extensions.recomputeThreadDate
import com.glooboo.umessage.extensions.shouldUnarchive
import com.glooboo.umessage.extensions.updateConversationArchivedStatus
import com.glooboo.umessage.extensions.updateLastConversationMessage
import com.glooboo.umessage.helpers.refreshConversations
import com.glooboo.umessage.helpers.refreshMessages
import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.spam.mms.BlockedMmsStore

/** "Not spam" for a blocked MMS whose downloaded content was kept: it goes into the inbox like a fresh download. */
internal object RestoredMmsDelivery {
    private const val TAG = "uMessageReceive"

    /**
     * Writes the kept PDU to the system provider's inbox with mmslib's own persist step (the one every downloaded
     * MMS goes through), dated when it was received, and updates the chat list. No notification: the user is looking
     * at it. Returns the new MMS id (for Undo), or null when nothing was written. Once the provider has it, nothing
     * below may fail the restore.
     */
    fun deliver(context: Context, message: BlockedMessage): Long? {
        val name = message.mediaFile ?: return null
        val pdu = BlockedMmsStore.pduFile(context, name)?.takeIf { it.isFile }?.readBytes() ?: return null
        val config = MmsConfig.Overridden(MmsConfig(context), null)
        // Empty location / transaction id: the notification-ind is long gone, and persist() binds both in queries.
        val uri: Uri = DownloadRequest.persist(context, pdu, config, "", "", message.subscriptionId, context.packageName)
            ?: return null

        runCatching {
            // persist() stamps the time of the restore; keep the thread in order with the time it really arrived.
            val values = ContentValues(1).apply { put(Mms.DATE, message.receivedAt / 1000) }
            context.contentResolver.update(uri, values, null, null)
        }.onFailure { Log.w(TAG, "Restored MMS keeps the restore time", it) }

        runCatching {
            val mms = context.getMMS(uri) ?: return@runCatching
            // The thread carries the restore time; date it by its newest message, as the chat list later will.
            context.recomputeThreadDate(mms.threadId)
            context.getConversations(mms.threadId).firstOrNull()?.let { conv ->
                val messageSeconds = (message.receivedAt / 1000).toInt()
                if (conv.date > messageSeconds) {
                    val cachedDate = context.conversationsDB.getConversationWithThreadId(mms.threadId)?.date ?: 0
                    conv.date = maxOf(cachedDate, messageSeconds)
                }
                context.insertOrUpdateConversation(conv)
            }
            if (context.shouldUnarchive()) context.updateConversationArchivedStatus(mms.threadId, false)
        }.onFailure { Log.w(TAG, "Restored MMS is in the inbox but the chat list was not updated", it) }
        runCatching { refreshMessages() }
        runCatching { refreshConversations() }
        return ContentUris.parseId(uri)
    }
}

/** Undo of "Not spam": takes a restored SMS or MMS back out of the inbox before it goes back to Blocked. */
internal object RestoredRemoval {
    private const val TAG = "uMessageReceive"
    private const val GONE = -1L

    /**
     * Deletes the system SMS (or MMS) [id] and its copy in the app's message cache, then dates the thread by what is
     * left; a thread left empty drops out of the chat list on the refresh. True when the message is gone (or already
     * was, say the user deleted it within the undo window), false when the provider could not be read or changed.
     */
    fun remove(context: Context, id: Long, isMms: Boolean): Boolean {
        val uri = ContentUris.withAppendedId(if (isMms) Mms.CONTENT_URI else Sms.CONTENT_URI, id)
        // No cursor at all means the provider could not be asked, which is not the same as "it is gone".
        val threadId = runCatching {
            context.contentResolver.query(uri, arrayOf(Sms.THREAD_ID), null, null, null)
                ?.use { if (it.moveToFirst()) it.getLong(0) else GONE }
        }.onFailure { Log.w(TAG, "Restored message could not be looked up for undo", it) }.getOrNull() ?: return false
        if (threadId == GONE) return true
        val deleted = runCatching { context.contentResolver.delete(uri, null, null) }
            .onFailure { Log.w(TAG, "Restored message could not be taken out of the inbox", it) }
        if (deleted.isFailure) return false
        runCatching { context.messagesDB.delete(id, isMms) }
        runCatching { context.updateLastConversationMessage(threadId) }
        runCatching { refreshMessages() }
        runCatching { refreshConversations() }
        return true
    }
}

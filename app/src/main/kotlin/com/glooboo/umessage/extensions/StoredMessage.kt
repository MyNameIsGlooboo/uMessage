package com.glooboo.umessage.extensions

import android.content.ContentUris
import android.content.Context
import android.provider.Telephony.Mms
import android.provider.Telephony.Sms
import androidx.core.net.toUri
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.models.RecycleBinMessage
import com.glooboo.umessage.spam.SpamThreads
import com.google.android.mms.pdu_alt.PduHeaders
import org.fossify.commons.helpers.isQPlus

/**
 * The phone's store row of a message, read to compare it with the cached copy. [date] is in seconds, as the cache
 * keeps it: SMS rows store milliseconds, MMS rows seconds.
 */
internal data class StoredMessage(
    val threadId: Long,
    val date: Int,
    val body: String,
    val sender: String,
    val received: Boolean,
)

/**
 * The store row [id] of [isMMS]'s kind: null when the row is gone, an exception when the store cannot be read. Shared
 * with Spam auto-delete, which treats both as "not the same message".
 */
internal fun Context.readStoredMessage(id: Long, isMMS: Boolean): StoredMessage? =
    if (isMMS) readStoredMms(id) else readStoredSms(id)

private fun Context.readStoredSms(id: Long): StoredMessage? {
    val cursor = contentResolver.query(
        ContentUris.withAppendedId(Sms.CONTENT_URI, id),
        arrayOf(Sms.THREAD_ID, Sms.DATE, Sms.BODY, Sms.ADDRESS, Sms.TYPE), null, null, null,
    ) ?: error("no cursor")
    return cursor.use { c ->
        if (!c.moveToFirst()) return null
        fun at(column: String) = c.getColumnIndexOrThrow(column)
        StoredMessage(
            threadId = c.getLong(at(Sms.THREAD_ID)),
            date = (c.getLong(at(Sms.DATE)) / MILLIS).toInt(),
            body = c.getString(at(Sms.BODY)).orEmpty(),
            sender = c.getString(at(Sms.ADDRESS)).orEmpty(),
            received = c.getInt(at(Sms.TYPE)) == Sms.MESSAGE_TYPE_INBOX,
        )
    }
}

private fun Context.readStoredMms(id: Long): StoredMessage? {
    val cursor = contentResolver.query(
        ContentUris.withAppendedId(Mms.CONTENT_URI, id),
        arrayOf(Mms.THREAD_ID, Mms.DATE, Mms.MESSAGE_BOX), null, null, null,
    ) ?: error("no cursor")
    val row = cursor.use { c ->
        if (!c.moveToFirst()) return null
        fun at(column: String) = c.getColumnIndexOrThrow(column)
        Triple(c.getLong(at(Mms.THREAD_ID)), c.getLong(at(Mms.DATE)).toInt(), c.getInt(at(Mms.MESSAGE_BOX)))
    }
    // The parts and the FROM address are read here, not through the swallowing helpers: a store that cannot answer
    // must fail the delete rather than look like a message with no text and no sender.
    val partsUri = if (isQPlus()) Mms.Part.CONTENT_URI else "content://mms/part".toUri()
    val parts = contentResolver.query(
        partsUri,
        arrayOf(Mms.Part.MSG_ID, Mms.Part.CONTENT_TYPE, Mms.Part.TEXT),
        "${Mms.Part.MSG_ID} = ?",
        arrayOf(id.toString()),
        "${Mms._ID} ASC",
    ) ?: error("no cursor")
    var body = ""
    parts.use { c ->
        while (c.moveToNext()) {
            val type = c.getString(c.getColumnIndexOrThrow(Mms.Part.CONTENT_TYPE)).orEmpty()
            if (type == "text/plain") {
                body = joinMmsText(body, c.getString(c.getColumnIndexOrThrow(Mms.Part.TEXT)).orEmpty())
            }
        }
    }
    val from = contentResolver.query(
        "${Mms.CONTENT_URI}/$id/addr".toUri(),
        arrayOf(Mms.Addr.ADDRESS),
        "${Mms.Addr.TYPE} = ?",
        arrayOf(PduHeaders.FROM.toString()),
        null,
    ) ?: error("no cursor")
    val sender = from.use { c ->
        if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(Mms.Addr.ADDRESS)).orEmpty() else ""
    }
    return StoredMessage(
        threadId = row.first,
        date = row.second,
        body = body,
        sender = sender,
        received = row.third == Mms.MESSAGE_BOX_INBOX,
    )
}

/**
 * Deletes a message for good. A message in the recycle bin is checked against the store row first: only when the row
 * still is the binned message (same chat, date, body, direction and, when received, sender, and not newer than the
 * entry) may the store row go. A row that is a different message under a reused id is left alone; the bin entry and
 * its stale cached copy are dropped so the new message is cached and shown again. A message not in the bin is deleted
 * as before, except from the recycle-bin view: a stale entry there must never delete a store row chosen by its id.
 */
fun Context.deleteBinnedMessage(
    id: Long,
    isMMS: Boolean,
    isDefault: Boolean,
    fromBinView: Boolean = false,
): DeleteResult {
    if (!isDefault) return DeleteResult.NOT_DEFAULT
    val entry = messagesDB.getRecycleBinEntry(id, isMMS)
    if (entry == null) {
        return if (fromBinView) DeleteResult.DELETED else deleteMessage(id, isMMS, true)
    }
    val cached = messagesDB.getMessage(id, isMMS)
    return if (cached == null) dropBinEntry(id, isMMS) else deleteBinnedCopy(id, isMMS, cached, entry)
}

private fun Context.dropBinEntry(id: Long, isMMS: Boolean): DeleteResult {
    messagesDB.deleteFromRecycleBin(id, isMMS)
    return DeleteResult.DELETED
}

private fun Context.deleteBinnedCopy(
    id: Long,
    isMMS: Boolean,
    cached: Message,
    entry: RecycleBinMessage,
): DeleteResult {
    val stored = runCatching { readStoredMessage(id, isMMS) }
        .getOrElse { return DeleteResult.FAILED }
    if (stored == null) return deleteMessage(id, isMMS, true)
    if (!matchesBinEntry(stored, cached, entry)) {
        messagesDB.delete(id, isMMS)
        return DeleteResult.DELETED
    }
    return deleteMessage(id, isMMS, true)
}

private fun Context.matchesBinEntry(stored: StoredMessage, cached: Message, entry: RecycleBinMessage): Boolean =
    stored.threadId == cached.threadId &&
        stored.date == cached.date &&
        stored.body == cached.body &&
        stored.received == cached.isReceivedMessage() &&
        (!stored.received || senderMatchesBinCopy(stored, cached)) &&
        stored.date * MILLIS <= entry.deletedTS + TOLERANCE_MS

/**
 * The bin check accepts the FROM sender or, for a received MMS, the chat's single participant that the cache prefers.
 * Spam auto-delete keeps its own FROM-only check ([StoredMessage.sender]).
 */
private fun Context.senderMatchesBinCopy(stored: StoredMessage, cached: Message): Boolean {
    if (sameSender(stored.sender, cached.senderPhoneNumber)) return true
    if (!cached.isMMS) return false
    // The cached MMS's sender is the chat's single participant when it had one, FROM only when it did not.
    val participant = cached.participants.singleOrNull()?.phoneNumbers?.firstOrNull()?.value.orEmpty()
    return participant.isNotEmpty() && sameSender(participant, cached.senderPhoneNumber)
}

private fun Context.sameSender(a: String, b: String): Boolean =
    a == b || runCatching { SpamThreads.same(SpamThreads.readers(this), a, b) }.getOrDefault(false)

private const val MILLIS = 1000L
private const val TOLERANCE_MS = 60_000L

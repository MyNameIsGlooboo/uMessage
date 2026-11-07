package com.glooboo.umessage.messaging

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony.Sms
import android.telephony.SmsMessage
import android.util.Log
import android.widget.Toast
import com.klinker.android.send_message.Message
import com.klinker.android.send_message.Settings
import com.klinker.android.send_message.Transaction
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.extensions.toast
import com.glooboo.umessage.R
import com.glooboo.umessage.extensions.getThreadId
import com.glooboo.umessage.extensions.messagesDB
import com.glooboo.umessage.models.Message as CachedMessage
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.models.PhoneNumber
import org.fossify.commons.models.SimpleContact
import com.glooboo.umessage.extensions.isPlainTextMimeType
import com.glooboo.umessage.extensions.smsSender
import com.glooboo.umessage.messaging.SmsException.Companion.ERROR_PERSISTING_MESSAGE
import com.glooboo.umessage.messaging.SmsException.Companion.ERROR_SENDING_MESSAGE
import com.glooboo.umessage.models.Attachment
import com.glooboo.umessage.receivers.MmsSentReceiver

class MessagingUtils(val context: Context) {

    /**
     * Insert an SMS to the given URI with thread_id specified.
     */
    private fun insertSmsMessage(
        subId: Int,
        dest: String,
        text: String,
        timestamp: Long,
        threadId: Long,
        status: Int = Sms.STATUS_NONE,
        type: Int = Sms.MESSAGE_TYPE_OUTBOX,
        messageId: Long? = null
    ): Uri {
        val response: Uri?
        val values = ContentValues().apply {
            put(Sms.ADDRESS, dest)
            put(Sms.DATE, timestamp)
            put(Sms.READ, 1)
            put(Sms.SEEN, 1)
            put(Sms.BODY, text)

            // insert subscription id only if it is a valid one.
            if (subId != Settings.DEFAULT_SUBSCRIPTION_ID) {
                put(Sms.SUBSCRIPTION_ID, subId)
            }

            if (status != Sms.STATUS_NONE) {
                put(Sms.STATUS, status)
            }
            if (type != Sms.MESSAGE_TYPE_ALL) {
                put(Sms.TYPE, type)
            }
            if (threadId != -1L) {
                put(Sms.THREAD_ID, threadId)
            }
        }

        try {
            if (messageId != null) {
                val selection = "${Sms._ID} = ?"
                val selectionArgs = arrayOf(messageId.toString())
                val count = context.contentResolver.update(
                    Sms.CONTENT_URI, values, selection, selectionArgs
                )
                response = if (count > 0) {
                    Uri.parse("${Sms.CONTENT_URI}/${messageId}")
                } else {
                    null
                }
            } else {
                response = context.contentResolver.insert(Sms.CONTENT_URI, values)
            }
        } catch (e: Exception) {
            throw SmsException(ERROR_PERSISTING_MESSAGE, e)
        }
        return response ?: throw SmsException(ERROR_PERSISTING_MESSAGE)
    }

    /**
     * Send an SMS message given [text] and [addresses]. Every recipient is tried, even after one fails, and each
     * failed recipient's row is marked failed. A [SmsException] is thrown at the end if any recipient failed:
     * [ERROR_PERSISTING_MESSAGE] only when nothing at all was saved, [ERROR_SENDING_MESSAGE] otherwise, so callers
     * know the saved rows (sent or failed) are in the thread. [forwardLogId] is the forward-log row of a forward; it
     * travels in the sent report so a radio failure marks exactly that forward.
     */
    fun sendSmsMessage(
        text: String,
        addresses: Set<String>,
        subId: Int,
        requireDeliveryReport: Boolean,
        messageId: Long? = null,
        forwardLogId: Long? = null,
    ) {
        // Resending a group SMS: [messageId] is the group row. Each recipient has a row of their own, which keeps its
        // own status, so only the recipients whose copy failed are sent to again, each through their own row.
        val previousRows = if (addresses.size > 1 && messageId != null) {
            findRecipientRows(messageId, text, addresses)
        } else {
            emptyMap()
        }
        val groupRowUri = insertGroupRow(text, addresses, subId, messageId)

        val alreadySent = previousRows.filterValues { it.type != Sms.MESSAGE_TYPE_FAILED }.keys
        var firstError: Exception? = null
        var savedAny = groupRowUri != null
        for (address in addresses) {
            if (address in alreadySent) continue // this one already has it
            val rowId = if (addresses.size > 1) previousRows[address]?.id else messageId
            val recipientThreadId = context.getThreadId(address)
            val timestamp = System.currentTimeMillis()
            val messageUri = try {
                insertSmsMessage(
                    subId = subId, dest = address, text = text,
                    timestamp = timestamp, threadId = recipientThreadId,
                    messageId = rowId
                )
            } catch (e: Exception) {
                firstError = firstError ?: e
                continue
            }
            savedAny = true
            if (addresses.size > 1) cacheRecipientRow(messageUri, address, text, timestamp, recipientThreadId, subId)
            val sendError = sendThroughRow(messageUri, address, text, subId, requireDeliveryReport, forwardLogId)
            firstError = firstError ?: sendError
        }

        val error = firstError ?: return
        // The group summary row was saved as sent; it must not say sent when someone did not get the message.
        groupRowUri?.let { updateSmsMessageSendingStatus(it, Sms.Outbox.MESSAGE_TYPE_FAILED) }
        throw sendFailure(error, addresses.size, savedAny)
    }

    /** Inserts the summary row of a group SMS and returns its uri, or null when there is a single recipient. */
    private fun insertGroupRow(text: String, addresses: Set<String>, subId: Int, messageId: Long?): Uri? {
        if (addresses.size <= 1) return null
        // insert a dummy message for this thread if it is a group message
        val broadCastThreadId = context.getThreadId(addresses.toSet())
        val mergedAddresses = addresses.joinToString(ADDRESS_SEPARATOR)
        return insertSmsMessage(
            subId = subId, dest = mergedAddresses, text = text,
            timestamp = System.currentTimeMillis(), threadId = broadCastThreadId,
            // Sent, not delivered: STATUS_COMPLETE made the thread say "Delivered" with delivery reports off.
            // Each recipient's own row carries its delivery report, when one was asked for.
            status = Sms.STATUS_NONE, type = Sms.Sent.MESSAGE_TYPE_SENT,
            messageId = messageId
        )
    }

    /** Sends [text] to [address] through its saved row [messageUri]. On failure marks the row failed, returns why. */
    private fun sendThroughRow(
        messageUri: Uri,
        address: String,
        text: String,
        subId: Int,
        requireDeliveryReport: Boolean,
        forwardLogId: Long?,
    ): Exception? = try {
        context.smsSender.sendMessage(
            subId = subId, destination = address, body = text, serviceCenter = null,
            requireDeliveryReport = requireDeliveryReport, messageUri = messageUri,
            forwardLogId = forwardLogId,
        )
        null
    } catch (e: Exception) {
        updateSmsMessageSendingStatus(messageUri, Sms.Outbox.MESSAGE_TYPE_FAILED)
        e
    }

    /**
     * A group sent as separate texts puts each recipient's copy in that person's own chat, which no screen has open.
     * The chat list previews a chat by its newest saved message (DL-08), so without this copy in the app's cache the
     * chat moved up with the new time but kept showing the message before it.
     */
    private fun cacheRecipientRow(uri: Uri, address: String, text: String, timestamp: Long, threadId: Long, subId: Int) {
        runCatching {
            val id = uri.lastPathSegment?.toLongOrNull() ?: return
            if (threadId <= 0L) return
            val participant = SimpleContact(
                rawId = 0, contactId = 0, name = address, photoUri = "",
                phoneNumbers = arrayListOf(PhoneNumber(value = address, type = 0, label = "", normalizedNumber = address)),
                birthdays = ArrayList(), anniversaries = ArrayList(),
            )
            val message = CachedMessage(
                id = id, body = text, type = Sms.MESSAGE_TYPE_OUTBOX, status = Sms.STATUS_NONE,
                participants = arrayListOf(participant), date = (timestamp / 1000).toInt(), read = true,
                threadId = threadId, isMMS = false, attachment = null, senderPhoneNumber = "", senderName = "",
                senderPhotoUri = "", subscriptionId = subId,
            )
            // Insert or update: a resend reuses the row id, and its cached copy must carry the new time. The thread
            // screen sends on the main thread, where Room may not be used.
            ensureBackgroundThread { runCatching { context.messagesDB.insertOrUpdate(message) } }
        }
    }

    private class RecipientRow(val id: Long, val type: Int)

    /**
     * Each recipient's own row of the group SMS [groupRowId]: same text, sent to that address within a minute of the
     * group row (they are all written in the same send). Recipients without one are missing from the map.
     */
    private fun findRecipientRows(groupRowId: Long, text: String, addresses: Set<String>): Map<String, RecipientRow> {
        val groupDate = runCatching {
            context.contentResolver.query(
                Sms.CONTENT_URI, arrayOf(Sms.DATE), "${Sms._ID} = ?", arrayOf(groupRowId.toString()), null
            )?.use { if (it.moveToFirst()) it.getLong(0) else null }
        }.getOrNull() ?: return emptyMap()
        val rows = HashMap<String, RecipientRow>()
        for (address in addresses) {
            runCatching {
                context.contentResolver.query(
                    Sms.CONTENT_URI,
                    arrayOf(Sms._ID, Sms.TYPE),
                    "${Sms.ADDRESS} = ? AND ${Sms.BODY} = ? AND ${Sms._ID} != ? AND ${Sms.DATE} BETWEEN ? AND ?",
                    arrayOf(
                        address, text, groupRowId.toString(),
                        (groupDate - RECIPIENT_ROW_WINDOW_MS).toString(), (groupDate + RECIPIENT_ROW_WINDOW_MS).toString()
                    ),
                    "${Sms.DATE} DESC"
                )?.use { if (it.moveToFirst()) rows[address] = RecipientRow(it.getLong(0), it.getInt(1)) }
            }
        }
        return rows
    }

    fun updateSmsMessageSendingStatus(messageUri: Uri?, type: Int) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(Sms.Outbox.TYPE, type)
        }

        try {
            if (messageUri != null) {
                resolver.update(messageUri, values, null, null)
            } else {
                // mark latest sms as sent, need to check if this is still necessary (or reliable)
                // as this was taken from android-smsmms. The messageUri shouldn't be null anyway
                val cursor = resolver.query(Sms.Outbox.CONTENT_URI, null, null, null, null)
                cursor?.use {
                    if (cursor.moveToFirst()) {
                        @SuppressLint("Range")
                        val id = cursor.getString(cursor.getColumnIndex(Sms.Outbox._ID))
                        val selection = "${Sms._ID} = ?"
                        val selectionArgs = arrayOf(id.toString())
                        resolver.update(Sms.Outbox.CONTENT_URI, values, selection, selectionArgs)
                    }
                }
            }
        } catch (e: Exception) {
            context.showErrorToast(e)
        }
    }

    fun getSmsMessageFromDeliveryReport(intent: Intent): SmsMessage? {
        val pdu = intent.getByteArrayExtra("pdu") ?: return null
        val format = intent.getStringExtra("format")
        return try {
            SmsMessage.createFromPdu(pdu, format)
        } catch (_: Exception) {
            null // a malformed report: ignore it rather than crash
        }
    }

    /** Returns false if the attachment could not be read or the library refused the message, so nothing was queued. */
    @Deprecated("TODO: Move/rewrite MMS code into the app.")
    fun sendMmsMessage(
        text: String,
        addresses: List<String>,
        attachment: Attachment?,
        settings: Settings,
        messageId: Long? = null
    ): Boolean {
        val message = Message(text, addresses.toTypedArray())

        if (attachment != null) {
            // Never send the message without its attachment: if it cannot be read, nothing goes out.
            val added = try {
                val uri = attachment.getUri()
                context.contentResolver.openInputStream(uri)?.use {
                    val bytes = it.readBytes()
                    val mimeType = if (attachment.mimetype.isPlainTextMimeType()) {
                        "application/txt"
                    } else {
                        attachment.mimetype
                    }
                    val name = mmsPartName(attachment.filename, attachment.id, mimeType, System.currentTimeMillis())
                    message.addMedia(bytes, mimeType, name, name)
                    true
                } ?: false
            } catch (e: Exception) {
                reportSendFailure("Could not read the attachment", e)
                return false
            } catch (e: Error) {
                reportSendFailure("Could not read the attachment", e)
                return false
            }
            if (!added) {
                reportSendFailure("The attachment stream was empty", IllegalStateException("no stream"))
                return false
            }
        }

        val transaction = Transaction(context, settings)
        val mmsSentIntent = Intent(context, MmsSentReceiver::class.java)
        mmsSentIntent.putExtra(MmsSentReceiver.EXTRA_ORIGINAL_RESENT_MESSAGE_ID, messageId)
        transaction.setExplicitBroadcastForSentMms(mmsSentIntent)

        return try {
            transaction.sendNewMessage(message)
            true
        } catch (e: Exception) {
            reportSendFailure("The MMS library refused the message", e)
            false
        }
    }

    /** The user sees a plain "not sent" message; the full exception goes to the log (no text, numbers or content). */
    private fun reportSendFailure(what: String, error: Throwable) {
        Log.e(TAG, what, error)
        context.toast(R.string.message_send_retry_later, Toast.LENGTH_LONG)
    }

    fun maybeShowErrorToast(resultCode: Int, errorCode: Int, noDefault: Boolean = false) {
        context.getSmsSendingError(resultCode, errorCode, noDefault)?.let { msg ->
            context.toast(msg = msg, length = Toast.LENGTH_LONG)
        }
    }

    companion object {
        const val ADDRESS_SEPARATOR = "|"
        private const val TAG = "MessagingUtils"
        private const val RECIPIENT_ROW_WINDOW_MS = 60_000L
    }
}

/**
 * What [MessagingUtils.sendSmsMessage] throws when at least one of [recipientCount] recipients failed with [error]:
 * with one recipient exactly what went wrong, as before; else whether anything at all was saved ([savedAny]).
 */
private fun sendFailure(error: Exception, recipientCount: Int, savedAny: Boolean): Exception =
    if (recipientCount == 1) {
        error
    } else {
        SmsException(if (savedAny) ERROR_SENDING_MESSAGE else ERROR_PERSISTING_MESSAGE, error)
    }

package com.glooboo.umessage.messaging

import android.content.Context
import android.util.Log
import android.util.Patterns
import android.widget.Toast.LENGTH_LONG
import com.klinker.android.send_message.Settings
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ensureBackgroundThread
import com.glooboo.umessage.R
import com.glooboo.umessage.extensions.config
import com.glooboo.umessage.extensions.deleteMessage
import com.glooboo.umessage.extensions.getThreadId
import com.glooboo.umessage.extensions.messagingUtils
import com.glooboo.umessage.extensions.shortcutHelper
import com.glooboo.umessage.messaging.SmsException.Companion.EMPTY_DESTINATION_ADDRESS
import com.glooboo.umessage.messaging.SmsException.Companion.ERROR_PERSISTING_MESSAGE
import com.glooboo.umessage.messaging.SmsException.Companion.ERROR_SENDING_MESSAGE
import com.glooboo.umessage.models.Attachment
import com.glooboo.umessage.models.MessageKey

@Deprecated("TODO: Move/rewrite messaging config code into the app.")
fun Context.getSendMessageSettings(): Settings {
    val settings = Settings()
    settings.useSystemSending = true
    settings.deliveryReports = config.enableDeliveryReports
    val after = config.sendLongAsMmsAfterParts
    settings.sendLongAsMms = after != LongTextMms.NEVER
    // The library sends as MMS above this many parts; the setting says "from this many parts".
    settings.sendLongAsMmsAfter = (after - 1).coerceAtLeast(1)
    settings.group = config.sendGroupMessageMMS
    return settings
}

/** Q9: true only when the user chose a number of parts in Settings and [text] has at least that many. */
fun Context.isLongMmsMessage(text: String, settings: Settings = getSendMessageSettings()): Boolean {
    if (!settings.sendLongAsMms) return false
    return LongTextMms.shouldSendAsMms(LongTextMms.smsParts(text), settings.sendLongAsMmsAfter + 1)
}

/**
 * What a send handed to the system. [handedOff] is true when every part reached the provider (an SMS that was saved
 * and then failed counts, as before). A message with several attachments goes out as one MMS per attachment, so it
 * can go out in part: [remainingAttachments] are the ones not handed off, [sentCount] how many were, and [textSent]
 * whether the text went with the last one. A retry sends only [remainingAttachments], and the text only when it was
 * not [textSent], so nothing the recipient already has is sent twice.
 */
data class SendOutcome(
    val handedOff: Boolean,
    val remainingAttachments: List<Attachment> = emptyList(),
    val sentCount: Int = 0,
    val textSent: Boolean = false,
) {
    /** Some attachments went out and some did not. */
    val partlySent: Boolean get() = !handedOff && sentCount > 0
}

/** Same as [sendMessageDetailed], for callers that only need to know whether everything was handed off. */
fun Context.sendMessageCompat(
    text: String,
    addresses: List<String>,
    subId: Int?,
    attachments: List<Attachment>,
    resend: MessageKey? = null,
    onFailure: () -> Unit = {},
    onAttachmentHandedOff: (Attachment) -> Unit = {},
): Boolean =
    sendMessageDetailed(text, addresses, subId, attachments, resend, onFailure, onAttachmentHandedOff).handedOff

/**
 * Sends the message using the in-app SmsManager API wrappers if it's an SMS or using android-smsmms for MMS.
 * Errors are shown as toasts. [SendOutcome.handedOff] is false when the message never reached the provider (nothing
 * was saved, so nothing can be retried from the thread), true otherwise, including sends that were saved and then
 * failed; for several attachments see [SendOutcome].
 * [onFailure] runs, after the toast, whenever this call already knows the send failed, whatever it returns; callers
 * running in the background use it to tell the user, since a toast there is never seen. Failures reported later by
 * the radio go through the sent receivers instead. [onAttachmentHandedOff] is told about each attachment that was queued
 * (MS6), so a caller that holds the whole message can drop it and never send it twice on a later Retry.
 */
fun Context.sendMessageDetailed(
    text: String,
    addresses: List<String>,
    subId: Int?,
    attachments: List<Attachment>,
    resend: MessageKey? = null,
    onFailure: () -> Unit = {},
    onAttachmentHandedOff: (Attachment) -> Unit = {},
): SendOutcome {
    val settings = getSendMessageSettings()
    activeSendSubscriptionId(subId)?.let { settings.subscriptionId = it }

    val messagingUtils = messagingUtils
    val progress = SendProgress()
    val isMms = attachments.isNotEmpty() || isLongMmsMessage(text, settings)
            || addresses.size > 1 && settings.group
    // The failed message being resent is replaced only by a resend of the same type: SMS and MMS ids are separate
    // sequences, so handing an SMS id to the MMS path (or the other way round) would touch an unrelated message.
    val messageId = resend?.takeIf { it.isMMS == isMms }?.id
    if (isMms && messageId != null && attachments.size > 1) {
        return resendBundledMms(
            MessageKey(messageId, true), text, addresses, attachments, settings, onFailure, onAttachmentHandedOff,
        )
    }
    if (isMms) {
        sendMmsParts(messagingUtils, text, addresses, attachments, settings, messageId, progress, onAttachmentHandedOff)
    } else {
        sendSmsPart(messagingUtils, text, addresses, settings, messageId, progress)
    }
    if (progress.failed) {
        onFailure()
    }
    ensureBackgroundThread {
        val threadId = getThreadId(addresses.toSet())
        shortcutHelper.reportSendMessageUsage(threadId)
    }
    return SendOutcome(progress.handedOff, progress.remaining, progress.sentCount, progress.textSent)
}

/** What one [sendMessageDetailed] call has done so far. */
private class SendProgress {
    var handedOff = true
    var failed = false
    var sentCount = 0
    var textSent = false
    val remaining = ArrayList<Attachment>()
    val sentUris = ArrayList<String>()
}

/**
 * Retry of a failed MMS row that holds several attachments (one bundled MMS from another app, or from before each
 * picture went out on its own). Each part goes out as its own MMS, so the row cannot stand for all of them: the
 * status receivers would delete it when the first part reports, losing the parts that failed. So the parts are sent
 * without the row's id, the row is deleted here only when every part was handed off, and the parts that did go out
 * are remembered ([ResendLedgers]) so another Retry of the row sends only the rest.
 */
private fun Context.resendBundledMms(
    row: MessageKey,
    text: String,
    addresses: List<String>,
    attachments: List<Attachment>,
    settings: Settings,
    onFailure: () -> Unit,
    onAttachmentHandedOff: (Attachment) -> Unit,
): SendOutcome {
    val ledger = ResendLedgers.get(this, row)
    val todo = ledger.undelivered(attachments)
    val progress = SendProgress()
    if (todo.isEmpty() && ledger.textSent) {
        deleteMessage(row.id, true)
        ResendLedgers.forget(this, row)
        return SendOutcome(handedOff = true, sentCount = attachments.size, textSent = true)
    }
    val toSend = ledger.textToSend(text)
    sendMmsParts(messagingUtils, toSend, addresses, todo, settings, null, progress, onAttachmentHandedOff)
    if (progress.handedOff) {
        deleteMessage(row.id, true)
        ResendLedgers.forget(this, row)
    } else {
        ResendLedgers.save(this, row, ledger.plus(progress.sentUris, progress.textSent))
        onFailure()
    }
    return SendOutcome(progress.handedOff, progress.remaining, progress.sentCount, progress.textSent)
}

private fun sendMmsParts(
    messagingUtils: MessagingUtils,
    text: String,
    addresses: List<String>,
    attachments: List<Attachment>,
    settings: Settings,
    messageId: Long?,
    progress: SendProgress,
    onAttachmentHandedOff: (Attachment) -> Unit = {},
) {
    // we send all MMS attachments separately to reduces the chances of hitting provider MMS limit.
    if (attachments.isEmpty()) {
        progress.handedOff = messagingUtils.sendMmsMessage(text, addresses, null, settings, messageId)
        progress.failed = !progress.handedOff
        return
    }
    val lastIndex = attachments.lastIndex
    for (i in 0 until lastIndex) {
        val attachment = attachments[i]
        if (messagingUtils.sendMmsMessage("", addresses, attachment, settings, messageId)) {
            progress.sentCount++
            progress.sentUris += attachment.uriString
            onAttachmentHandedOff(attachment)
        } else {
            progress.remaining += attachment
            progress.handedOff = false
            progress.failed = true
        }
    }

    val lastAttachment = attachments[lastIndex]
    if (messagingUtils.sendMmsMessage(text, addresses, lastAttachment, settings, messageId)) {
        progress.sentCount++
        progress.sentUris += lastAttachment.uriString
        onAttachmentHandedOff(lastAttachment)
        progress.textSent = true
    } else {
        progress.remaining += lastAttachment
        progress.handedOff = false
        progress.failed = true
    }
}

private fun Context.sendSmsPart(
    messagingUtils: MessagingUtils,
    text: String,
    addresses: List<String>,
    settings: Settings,
    messageId: Long?,
    progress: SendProgress,
) {
    try {
        messagingUtils.sendSmsMessage(
            text = text,
            addresses = addresses.toSet(),
            subId = settings.subscriptionId,
            requireDeliveryReport = settings.deliveryReports,
            messageId = messageId
        )
    } catch (e: SmsException) {
        // A sending error leaves a failed row in the provider; the other errors leave nothing.
        progress.handedOff = e.errorCode == ERROR_SENDING_MESSAGE
        progress.failed = true
        when (e.errorCode) {
            EMPTY_DESTINATION_ADDRESS -> toast(id = R.string.empty_destination_address, length = LENGTH_LONG)
            ERROR_PERSISTING_MESSAGE -> toast(id = R.string.unable_to_save_message, length = LENGTH_LONG)
            ERROR_SENDING_MESSAGE -> toast(
                msg = getString(
                    R.string.sms_preparation_error_details,
                    getString(R.string.message_not_sent_short),
                    e.errorCode
                ),
                length = LENGTH_LONG
            )
        }
    } catch (e: Exception) {
        progress.handedOff = false
        progress.failed = true
        Log.e("Messaging", "Sending an SMS failed", e)
        toast(id = R.string.message_send_retry_later, length = LENGTH_LONG)
    }
}

/**
 * Check if a given "address" is a short code.
 * There's not much info available on these special numbers, even the wikipedia page (https://en.wikipedia.org/wiki/Short_code)
 * contains outdated information regarding max number of digits. The exact parameters for short codes can vary by country and by carrier.
 */
fun isShortCodeWithLetters(address: String): Boolean {
    if (Patterns.EMAIL_ADDRESS.matcher(address).matches()) {
        // emails are not short codes: https://github.com/FossifyOrg/Messages/issues/115
        return false
    }

    return address.any { it.isLetter() }
}

package com.glooboo.umessage.messaging

import android.content.Context
import android.telephony.SmsMessage
import android.util.Log
import android.util.Patterns
import android.widget.Toast.LENGTH_LONG
import com.klinker.android.send_message.Settings
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ensureBackgroundThread
import com.glooboo.umessage.R
import com.glooboo.umessage.extensions.config
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
    settings.sendLongAsMms = config.sendLongMessageMMS
    settings.sendLongAsMmsAfter = 1
    settings.group = config.sendGroupMessageMMS
    return settings
}

fun Context.isLongMmsMessage(text: String, settings: Settings = getSendMessageSettings()): Boolean {
    val data = SmsMessage.calculateLength(text, false)
    val numPages = data.first()
    return numPages > settings.sendLongAsMmsAfter && settings.sendLongAsMms
}

/**
 * Sends the message using the in-app SmsManager API wrappers if it's an SMS or using android-smsmms for MMS.
 * Errors are shown as toasts. Returns false when the message never reached the provider (nothing was saved, so
 * nothing can be retried from the thread), true otherwise, including sends that were saved and then failed.
 * [onFailure] runs, after the toast, whenever this call already knows the send failed, whatever it returns; callers
 * running in the background use it to tell the user, since a toast there is never seen. Failures reported later by
 * the radio go through the sent receivers instead.
 */
fun Context.sendMessageCompat(
    text: String,
    addresses: List<String>,
    subId: Int?,
    attachments: List<Attachment>,
    resend: MessageKey? = null,
    onFailure: () -> Unit = {}
): Boolean {
    val settings = getSendMessageSettings()
    if (subId != null) {
        settings.subscriptionId = subId
    }

    val messagingUtils = messagingUtils
    var handedOff = true
    var failed = false
    val isMms = attachments.isNotEmpty() || isLongMmsMessage(text, settings)
            || addresses.size > 1 && settings.group
    // The failed message being resent is replaced only by a resend of the same type: SMS and MMS ids are separate
    // sequences, so handing an SMS id to the MMS path (or the other way round) would touch an unrelated message.
    val messageId = resend?.takeIf { it.isMMS == isMms }?.id
    if (isMms) {
        // we send all MMS attachments separately to reduces the chances of hitting provider MMS limit.
        if (attachments.isNotEmpty()) {
            val lastIndex = attachments.lastIndex
            if (attachments.size > 1) {
                for (i in 0 until lastIndex) {
                    val attachment = attachments[i]
                    if (!messagingUtils.sendMmsMessage("", addresses, attachment, settings, messageId)) {
                        handedOff = false
                        failed = true
                    }
                }
            }

            val lastAttachment = attachments[lastIndex]
            if (!messagingUtils.sendMmsMessage(text, addresses, lastAttachment, settings, messageId)) {
                handedOff = false
                failed = true
            }
        } else {
            handedOff = messagingUtils.sendMmsMessage(text, addresses, null, settings, messageId)
            failed = !handedOff
        }
    } else {
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
            handedOff = e.errorCode == ERROR_SENDING_MESSAGE
            failed = true
            when (e.errorCode) {
                EMPTY_DESTINATION_ADDRESS -> toast(
                    id = R.string.empty_destination_address,
                    length = LENGTH_LONG
                )

                ERROR_PERSISTING_MESSAGE -> toast(
                    id = R.string.unable_to_save_message,
                    length = LENGTH_LONG
                )

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
            handedOff = false
            failed = true
            Log.e("Messaging", "Sending an SMS failed", e)
            toast(id = R.string.message_send_retry_later, length = LENGTH_LONG)
        }
    }
    if (failed) {
        onFailure()
    }
    ensureBackgroundThread {
        val threadId = getThreadId(addresses.toSet())
        shortcutHelper.reportSendMessageUsage(threadId)
    }
    return handedOff
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

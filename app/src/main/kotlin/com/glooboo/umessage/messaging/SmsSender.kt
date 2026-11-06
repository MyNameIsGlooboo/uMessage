package com.glooboo.umessage.messaging

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.telephony.PhoneNumberUtils
import org.fossify.commons.helpers.isSPlus
import com.glooboo.umessage.messaging.SmsException.Companion.EMPTY_DESTINATION_ADDRESS
import com.glooboo.umessage.messaging.SmsException.Companion.ERROR_SENDING_MESSAGE
import com.glooboo.umessage.receivers.SendStatusReceiver
import com.glooboo.umessage.receivers.SmsStatusDeliveredReceiver
import com.glooboo.umessage.receivers.SmsStatusSentReceiver

/** Class that sends chat message via SMS. */
class SmsSender(val app: Application) {

    // not sure what to do about this yet. this is the default as per android-smsmms
    private val sendMultipartSmsAsSeparateMessages = false

    // This should be called from a RequestWriter queue thread
    fun sendMessage(
        subId: Int, destination: String, body: String, serviceCenter: String?,
        requireDeliveryReport: Boolean, messageUri: Uri, forwardLogId: Long? = null,
    ) {
        var dest = destination
        require(body.isNotEmpty()) { "SmsSender: empty text message" }
        // remove spaces and dashes from destination number
        // (e.g. "801 555 1212" -> "8015551212")
        // (e.g. "+8211-123-4567" -> "+82111234567")
        dest = PhoneNumberUtils.stripSeparators(dest)

        if (dest.isEmpty()) {
            throw SmsException(EMPTY_DESTINATION_ADDRESS)
        }
        // Divide the input message by SMS length limit
        val smsManager = getSmsManager(app, subId)
        val messages = smsManager.divideMessage(body)
        if (messages == null || messages.size < 1) {
            throw SmsException(ERROR_SENDING_MESSAGE)
        }
        // Actually send the sms
        sendInternal(
            subId, dest, messages, serviceCenter, requireDeliveryReport, messageUri, forwardLogId
        )
    }

    // Actually sending the message using SmsManager
    private fun sendInternal(
        subId: Int, dest: String,
        messages: ArrayList<String>, serviceCenter: String?,
        requireDeliveryReport: Boolean, messageUri: Uri, forwardLogId: Long?,
    ) {
        val smsManager = getSmsManager(app, subId)
        val messageCount = messages.size
        val deliveryIntents = ArrayList<PendingIntent?>(messageCount)
        val sentIntents = ArrayList<PendingIntent>(messageCount)

        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (isSPlus()) {
            flags = flags or PendingIntent.FLAG_MUTABLE
        }

        for (i in 0 until messageCount) {
            // Make pending intents different for each message part
            val partId = if (messageCount <= 1) 0 else i + 1
            if (requireDeliveryReport && i == messageCount - 1) {
                deliveryIntents.add(
                    PendingIntent.getBroadcast(
                        app,
                        partId,
                        getDeliveredStatusIntent(messageUri, subId),
                        flags
                    )
                )
            } else {
                deliveryIntents.add(null)
            }
            sentIntents.add(
                PendingIntent.getBroadcast(
                    app,
                    partId,
                    getSendStatusIntent(messageUri, subId, messageCount, forwardLogId),
                    flags
                )
            )
        }
        SentParts.start(app, messageUri)
        try {
            if (sendMultipartSmsAsSeparateMessages) {
                // If multipart sms is not supported, send them as separate messages
                for (i in 0 until messageCount) {
                    smsManager.sendTextMessage(
                        dest,
                        serviceCenter,
                        messages[i],
                        sentIntents[i],
                        deliveryIntents[i]
                    )
                }
            } else {
                smsManager.sendMultipartTextMessage(
                    dest, serviceCenter, messages, sentIntents, deliveryIntents
                )
            }
        } catch (e: Exception) {
            throw SmsException(ERROR_SENDING_MESSAGE, e)
        }
    }

    private fun getSendStatusIntent(requestUri: Uri, subId: Int, partCount: Int, forwardLogId: Long?): Intent {
        val intent = Intent(SendStatusReceiver.SMS_SENT_ACTION, requestUri, app, SmsStatusSentReceiver::class.java)
        intent.putExtra(SendStatusReceiver.EXTRA_SUB_ID, subId)
        // Each part reports on its own; the receiver needs the total to know when the whole message is sent.
        intent.putExtra(SendStatusReceiver.EXTRA_PART_COUNT, partCount)
        // A forward: its log row, so a failure the radio reports marks exactly that forward.
        if (forwardLogId != null) intent.putExtra(SmsStatusSentReceiver.EXTRA_FORWARD_LOG_ID, forwardLogId)
        return intent
    }

    private fun getDeliveredStatusIntent(requestUri: Uri, subId: Int): Intent {
        val intent = Intent(SendStatusReceiver.SMS_DELIVERED_ACTION, requestUri, app, SmsStatusDeliveredReceiver::class.java)
        intent.putExtra(SendStatusReceiver.EXTRA_SUB_ID, subId)
        return intent
    }

    companion object {
        @Volatile
        private var instance: SmsSender? = null
        fun getInstance(app: Application): SmsSender {
            return instance ?: synchronized(this) {
                instance ?: SmsSender(app).also { instance = it }
            }
        }
    }
}

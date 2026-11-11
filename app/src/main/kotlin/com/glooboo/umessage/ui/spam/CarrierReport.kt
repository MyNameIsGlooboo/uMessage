package com.glooboo.umessage.ui.spam

import android.content.Context
import com.glooboo.umessage.extensions.config
import com.glooboo.umessage.extensions.messagingUtils
import com.glooboo.umessage.spam.BlockedMessage
import com.klinker.android.send_message.Settings

/** Sends [text] as an SMS to [number]. Tests give [CarrierReport] a fake one, so no test ever sends a real SMS. */
fun interface ReportSender {
    fun send(context: Context, text: String, number: String, subscriptionId: Int)
}

/** The normal path: the app's own SMS sending, from the SIM [subscriptionId] when it is a real one. */
object SmsReportSender : ReportSender {
    override fun send(context: Context, text: String, number: String, subscriptionId: Int) {
        context.messagingUtils.sendSmsMessage(
            text = text,
            addresses = setOf(number),
            subId = subscriptionId.takeIf { it >= 0 } ?: Settings.DEFAULT_SUBSCRIPTION_ID,
            requireDeliveryReport = false,
        )
    }
}

/**
 * I32: "Report spam" to the carrier. Many carriers take a forwarded spam text at a short number (7726 in many
 * countries), which needs no internet. The number is empty until the user enters their carrier's own, so nothing can
 * be reported by accident or to a wrong number. It is a real, possibly paid SMS: [send] is only ever called after the
 * user confirmed it in the dialog, and it sends nothing but the text of the one message being reported and, under
 * it, the number it came from (carriers answer a report with "which number sent this?").
 */
class CarrierReport(context: Context, private val sender: ReportSender = SmsReportSender) {

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The carrier's short number, digits only; empty = not set, and Report spam is not offered. */
    var number: String
        get() = prefs.getString(KEY_NUMBER, null).orEmpty()
        set(value) = prefs.edit().putString(KEY_NUMBER, value.trim()).apply()

    /**
     * Sends [message]'s text and its sender to [number], as one SMS. False (and nothing sent) when no number is set or
     * there is no text.
     */
    fun send(message: BlockedMessage): Boolean {
        val target = number
        if (!isValidNumber(target) || !message.hasText) return false
        sender.send(app, reportText(message), target, message.subscriptionId)
        return true
    }

    companion object {
        private const val PREFS_NAME = "umessage_carrier_report"
        private const val KEY_NUMBER = "carrier_spam_number"
        private const val MIN_DIGITS = 3
        private const val MAX_DIGITS = 8

        /** What a report says: the message text, then the number or name it came from on a line of its own. */
        fun reportText(message: BlockedMessage): String {
            val sender = message.address.trim()
            return if (sender.isEmpty()) message.body else message.body.trimEnd() + "\n" + sender
        }

        /** A short number: 3 to 8 digits and nothing else. */
        fun isValidNumber(value: String): Boolean =
            value.length in MIN_DIGITS..MAX_DIGITS && value.all { it in '0'..'9' }

        /**
         * The one message a Report spam tap would send: the newest readable message of the single selected sender.
         * Null when not exactly one sender is selected, so one tap never sends several paid SMS.
         */
        fun pick(groups: List<SenderGroup>, selected: Set<String>): BlockedMessage? =
            selected.singleOrNull()?.let { address ->
                groups.firstOrNull { it.address == address }?.messages?.firstOrNull { it.hasText }
            }
    }
}

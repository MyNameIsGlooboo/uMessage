package com.glooboo.umessage.ui.thread

import com.glooboo.umessage.spam.TextNormalizer
import com.glooboo.umessage.spam.engine.OtpDetector

/** I04: when the "Not in your contacts" card shows, and when it adds its caution line. Pure, so it is easy to test. */
object UnknownCardLogic {

    /**
     * Shown for a stranger who has written to the user, in a thread that is not in the recycle bin, whose sender is
     * not a sender name nobody can answer, has no rule yet and whose card was not dismissed.
     */
    fun shouldShow(
        isUnknown: Boolean,
        hasReceived: Boolean,
        isRecycleBin: Boolean,
        isSenderName: Boolean,
        hasRule: Boolean,
        dismissed: Boolean,
    ): Boolean = isUnknown && hasReceived && !isRecycleBin && !isSenderName && !hasRule && !dismissed

    /** The newest received message holds a link, an amount of money or a request to send a code. */
    fun needsCaution(body: String?): Boolean {
        if (body.isNullOrBlank()) return false
        if (OtpDetector.containsUrl(body)) return true
        val text = TextNormalizer.lower(body)
        return listOf(amount, codeRequest, codeRequestFa).any { it.containsMatchIn(text) }
    }

    private val amount = Regex(
        """[$€£]\s?\d""" +
            """|\d[\d,.]*\s?([$€£]|usd|eur|gbp|dollars?|euros?|rials?|tomans?|ریال|تومان|دلار|یورو)""" +
            """|(amount|مبلغ)\W{0,3}\d""",
    )
    private val codeRequest = Regex(
        """(send|share|tell|give|forward|read|reply|text)\b.{0,25}\b(code|otp|pin|password)""" +
            """|(code|otp|pin|password)\b.{0,25}\b(send|share|tell|forward|read)\b""",
    )
    private val codeRequestFa = Regex(
        """(کد|رمز).{0,25}(بفرست|بگو|ارسال کن|ارسال کنید|بده|بخوان|اعلام کن)""" +
            """|(بفرست|بگو|ارسال کن|ارسال کنید|بده|بخوان|اعلام کن).{0,25}(کد|رمز)""",
    )
}

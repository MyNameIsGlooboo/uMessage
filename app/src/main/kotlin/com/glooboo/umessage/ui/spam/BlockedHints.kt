package com.glooboo.umessage.ui.spam

import com.glooboo.umessage.spam.engine.OtpDetector

/**
 * Pure text checks behind two hints on the Blocked rows (BR-01, BR-18). They only read the message text, never leave
 * the device, and are deliberately conservative: a hint is a nudge to look, not a verdict.
 */
object BlockedHints {

    private val link = Regex(
        """(https?://|www\.|\b[a-z0-9-]+\.(com|ir|net|org|info|xyz|ly|me|co|top|click|link)\b)""",
        RegexOption.IGNORE_CASE,
    )
    private val code = Regex("""(?<!\d)\d{4,8}(?!\d)""")

    private val bankWords = listOf(
        "bank", "account", "transaction", "debit", "credit", "balance", "withdraw", "deposit",
        "بانک", "حساب", "تراکنش", "مانده", "برداشت", "واریز",
    )

    // spamui-8: "account locked, call 12345" is a scam's ask, not a notice: a bank text that asks to call is no hint.
    private val askWords = listOf("call", "dial", "reply", "contact", "whatsapp", "تماس", "زنگ", "پاسخ")

    /** Persian and Arabic-Indic digits to ASCII, so one pattern covers every script. */
    fun asciiDigits(text: String): String = buildString(text.length) {
        for (c in text) {
            append(
                when (c) {
                    in '۰'..'۹' -> '0' + (c - '۰')
                    in '٠'..'٩' -> '0' + (c - '٠')
                    else -> c
                },
            )
        }
    }

    /** Whether [body] contains a web address: the filter's own link test ([OtpDetector.containsUrl]) or a plain one. */
    fun hasLink(body: String): Boolean = link.containsMatchIn(body) || OtpDetector.containsUrl(body)

    /**
     * Whether a blocked [body] looks like a verification code, or a bank notice without a link. A code counts even
     * with a link (owner decision: Smart mode lets those through, so Strict's held ones are flagged too); a bank
     * notice with a link, or one that asks to call or reply, is left out: phishing copies exactly those texts.
     */
    fun looksImportant(body: String): Boolean {
        if (body.isBlank()) return false
        // B9: the filter's own code test, so "important" and "held as a code" agree ("pin" is not "shipping").
        if (OtpDetector.looksLikeOtp(body)) return true
        if (hasLink(body)) return false
        val text = asciiDigits(body).lowercase()
        if (askWords.any { it in text }) return false
        return bankWords.any { it in text } && code.containsMatchIn(text)
    }
}

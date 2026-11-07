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
     * Whether a blocked [body] looks like a verification code or a bank notice without a link. Texts with a link are
     * left out: phishing copies exactly these texts, so they should not be flagged as safe to restore.
     */
    fun looksImportant(body: String): Boolean {
        if (body.isBlank() || hasLink(body)) return false
        val text = asciiDigits(body).lowercase()
        // B9: the filter's own code test, so "important" and "held as a code" agree ("pin" is not "shipping").
        val hasCode = OtpDetector.looksLikeOtp(body)
        val bank = bankWords.any { it in text } && code.containsMatchIn(text)
        return hasCode || bank
    }
}

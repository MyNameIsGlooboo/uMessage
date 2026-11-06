package com.glooboo.umessage.ui.spam

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

    private val codeWords = listOf(
        "code", "otp", "pin", "verification", "verify", "password",
        "کد", "رمز", "تایید", "تأیید", "یکبار", "یک‌بار",
    )
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

    /** Whether [body] contains a web address. */
    fun hasLink(body: String): Boolean = link.containsMatchIn(body)

    /**
     * Whether a blocked [body] looks like a verification code or a bank notice without a link. Texts with a link are
     * left out: phishing copies exactly these texts, so they should not be flagged as safe to restore.
     */
    fun looksImportant(body: String): Boolean {
        if (body.isBlank() || hasLink(body)) return false
        val text = asciiDigits(body).lowercase()
        val hasCode = code.containsMatchIn(text) && codeWords.any { it in text }
        val bank = bankWords.any { it in text } && code.containsMatchIn(text)
        return hasCode || bank
    }
}

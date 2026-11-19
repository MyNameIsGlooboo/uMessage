package com.glooboo.umessage.spam.engine

/**
 * The 9-10 digit first password ("رمز شخصی شما 1234567890"): a number longer than [OtpDetector.MAX_CODE_DIGITS] is a
 * code only when a password word ([OtpDetector.passwordKeywords]) stands a few plain words before or after it.
 */
internal object OtpPasswords {
    private val before = Regex(
        OtpDetector.keywordPattern(OtpDetector.passwordKeywords) + OtpDetector.NEAR + "$",
        RegexOption.IGNORE_CASE,
    )
    private val after = Regex(
        "^" + OtpDetector.NEAR + OtpDetector.keywordPattern(OtpDetector.passwordKeywords),
        RegexOption.IGNORE_CASE,
    )

    /** Whether [value] has more digits than a code of [OtpDetector.codeShape]. */
    fun isLong(value: String): Boolean = value.count { it.isDigit() } > OtpDetector.MAX_CODE_DIGITS

    /** Whether the number at [range] of [text] (folded) has a password word within [window] characters. */
    fun nextTo(text: String, range: IntRange, window: Int): Boolean {
        val head = text.substring(maxOf(0, range.first - window), range.first)
        val tail = text.substring(range.last + 1, minOf(text.length, range.last + 1 + window))
        return before.containsMatchIn(head) || after.containsMatchIn(tail)
    }
}

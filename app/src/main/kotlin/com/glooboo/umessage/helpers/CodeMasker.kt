package com.glooboo.umessage.helpers

import com.glooboo.umessage.spam.TextNormalizer

/**
 * Hides the verification code of a message (the same value [OtpCodes.primary] offers for "Copy code") and leaves
 * every other character as it was written: Persian digits, invisible marks and line breaks of the rest of the text
 * stay. Used for the lock-screen version of a notification and for forwards that carry a code.
 *
 * The code is found in the folded text ([TextNormalizer.fold]) and each match is mapped back to its place in the
 * original, so the replaced part is exactly the code as the sender wrote it. Every other occurrence of the same code
 * in the message is hidden too. A message without a recognised code comes back unchanged.
 */
object CodeMasker {
    /** For notifications, which show any Unicode. */
    const val BULLETS = "••••"

    /** For a forward: stays in the GSM 7-bit alphabet, so the SMS does not switch to the 70-character encoding. */
    const val ASTERISKS = "****"

    /**
     * [body] with its code replaced by [mark], followed by the code's last two digits when [keepLastTwo] is on
     * ("Code: ****82"). A code of four digits keeps its last two as well, so use that setting with care.
     */
    fun mask(body: String, keepLastTwo: Boolean = false, mark: String = BULLETS): String {
        val folded = TextNormalizer.fold(body)
        val best = OtpCodes.primary(body) ?: return body
        // Where each folded character stands in the original (the fold only drops characters, never adds any).
        val origin = IntArray(folded.length)
        var next = 0
        for ((index, c) in body.withIndex()) {
            if (c in TextNormalizer.REMOVED) continue
            if (next >= origin.size) return body
            origin[next++] = index
        }
        if (next != origin.size) return body

        val spans = OtpCodes.spans(folded, best.value)
        val out = StringBuilder(body.length)
        var position = 0
        for (span in spans) {
            val start = origin[span.first]
            val end = origin[span.last] + 1
            if (start < position) continue
            out.append(body, position, start)
            out.append(mark)
            if (keepLastTwo) out.append(body.substring(start, end).filter { it.isDigit() }.takeLast(KEPT_DIGITS))
            position = end
        }
        out.append(body, position, body.length)
        return out.toString()
    }

    /** [mask] of [text] when [hide] ("Hide codes on the lock screen") is on, else [text] as it is. */
    fun maskIf(hide: Boolean, text: String): String = if (hide) mask(text) else text

    /**
     * The fail-closed mask for a forward (M3): [mask] first; when it finds no code to hide (the detector that called
     * this message a code and the extractor that locates it disagree, e.g. "482913G"), every run of 4 to 8 digits is
     * hidden instead, the shape the detector looks for. Invisible marks inside a run count as part of it. A code
     * written in groups ("48 29 13", "4829-13") is hidden as well ([maskSpacedCodes]), before the digit runs, so a
     * run never takes the first group and leaves the rest ("****-13").
     */
    fun maskForForward(body: String, keepLastTwo: Boolean = false, mark: String = ASTERISKS): String {
        val masked = mask(body, keepLastTwo, mark)
        return if (masked != body) {
            maskSpacedCodes(masked, mark)
        } else {
            maskDigitRuns(maskSpacedCodes(body, mark), mark)
        }
    }

    /** Every run of 4 to 8 digits (any script) replaced by [mark]; longer and shorter numbers stay. */
    fun maskDigitRuns(body: String, mark: String = ASTERISKS): String = digitRun.replace(body, mark)

    /**
     * Codes written in groups ("48 29 13", "4829-13", "۴۸ ۲۹ ۱۳"): two to four groups of 2 to 4 digits joined by one
     * space or hyphen, 4 to 8 digits in all, become [mark]. Neither [mask] nor [maskDigitRuns] sees such a code, so a
     * forward of a message judged to hold a code runs this too. A longer number written in groups (a phone number)
     * stays.
     */
    fun maskSpacedCodes(body: String, mark: String = ASTERISKS): String = spacedCode.replace(body) { match ->
        if (match.value.count { it.isDigit() } in MIN_CODE_DIGITS..MAX_CODE_DIGITS) mark else match.value
    }

    private val spacedCode = Regex(
        "(?<!\\p{Nd}[ \\u00A0-])(?<![\\p{Nd}\\p{L}])" +
            "\\p{Nd}{2,4}(?:[ \\u00A0-]\\p{Nd}{2,4}){1,3}" +
            "(?![ \\u00A0-]?\\p{Nd})(?!\\p{L})",
    )

    private val digitRun: Regex by lazy {
        val invisible = TextNormalizer.REMOVED.joinToString("") { "\\u%04x".format(it.code) }
        Regex("(?<![\\p{Nd}$invisible])\\p{Nd}(?:[$invisible]*\\p{Nd}){3,7}(?![$invisible]*\\p{Nd})")
    }

    private const val KEPT_DIGITS = 2
    private const val MIN_CODE_DIGITS = 4
    private const val MAX_CODE_DIGITS = 8
}

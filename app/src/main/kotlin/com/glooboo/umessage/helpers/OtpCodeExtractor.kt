package com.glooboo.umessage.helpers

import com.glooboo.umessage.spam.TextNormalizer
import com.glooboo.umessage.spam.engine.OtpDetector

/**
 * Finds the one-time code in a message body, for the notification's "Copy code", the list chip and the Codes filter.
 * [extract] answers through [OtpCodes], the one rule of the app; this object holds the part that picks a number out of
 * the text: keywords must be whole words ("pin" must not match "shipping", "code" must not match "barcode") and when
 * several numbers qualify the one closest to a keyword wins.
 *
 * The body is read through [TextNormalizer.fold], so Persian and Arabic digits come back as ASCII digits and the
 * keywords of [OtpDetector] ("رمز پویا", "کد تایید", "کدتان") are found. Word edges are explicit lookarounds, as
 * `\b` differs between the JVM and Android's ICU regex engine.
 *
 * A number is never the code when it is an amount (followed by ریال, تومان, %, a currency, or written with thousands
 * separators), part of a masked card or account number ("5022-29**-****-4417", "****1234"), a time or a date
 * ("14:32", "1403/08/11"), or a year next to the generic word "code" only.
 */
object OtpCodeExtractor {
    private val strongKeywords = Regex(OtpDetector.keywordPattern(OtpDetector.strongKeywords), RegexOption.IGNORE_CASE)
    private val weakKeywords = Regex(OtpDetector.keywordPattern(OtpDetector.weakKeywords), RegexOption.IGNORE_CASE)

    /** Characters that join digits into one bigger number: thousands and decimal separators, time and date marks. */
    private const val JOINERS = "\\-/:.,\u066B\u066C"

    /**
     * A code as [OtpDetector.codeShape] defines it (shared with the filter). Not glued to letters, digits, `*` or a
     * currency sign, not one part of a number joined by [JOINERS] ("1,250,000", "14:32", "5022-29**"), and not
     * followed by an amount's unit.
     */
    private val code = Regex(
        "(?<![\\p{L}\\p{N}.*$€£])(?<![\\p{N}*][$JOINERS])" +
            "(" + OtpDetector.codeShape + ")" +
            "(?![\\p{L}\\p{N}*])(?![$JOINERS][\\p{N}*])" + OtpDetector.NOT_AFTER,
        RegexOption.IGNORE_CASE,
    )
    private val year = Regex(OtpDetector.YEAR)
    private val separators = Regex("[\\s-]")

    /** The code to copy, with Latin digits, or null when the message holds none (see [OtpCodes.primary]). */
    fun extract(body: String): String? = OtpCodes.primary(body)?.value

    /**
     * The number closest to any code keyword in [text], which must already be folded by [TextNormalizer.fold]; group 1
     * is the code. This is the raw locator without the detector's vetoes and distance rule: [OtpCodes.locate] is the
     * one the app uses, and forwarding keeps this one as its fail-closed hint ([hasKeywordNearNumber]).
     */
    internal fun locate(text: String): MatchResult? {
        val strong = strongKeywords.findAll(text).map { it.range }.toList()
        val weak = weakKeywords.findAll(text).map { it.range }.toList()
        if (strong.isEmpty() && weak.isEmpty()) return null
        return code.findAll(text).mapNotNull { match ->
            // Next to the generic word "code" only, a year ("2026", "1403") is not the code.
            val near = if (year.matches(match.value)) strong else strong + weak
            near.minOfOrNull { distance(match.range, it) }?.let { match to it }
        }.minByOrNull { it.second }?.first
    }

    /** Whether any code-shaped number in [body] has a code keyword anywhere in the text: a deliberately wide net. */
    internal fun hasKeywordNearNumber(body: String): Boolean = locate(TextNormalizer.fold(body)) != null

    /** Every code-shaped number of the folded [text] (no keyword needed), for masking repeats of the same code. */
    internal fun codeShapes(text: String): Sequence<MatchResult> = code.findAll(text)

    /** [value] of a code match without its space or hyphen. */
    internal fun digitsOf(value: String): String = value.replace(separators, "")

    private fun distance(a: IntRange, b: IntRange): Int = when {
        a.last < b.first -> b.first - a.last
        b.last < a.first -> a.first - b.last
        else -> 0
    }
}

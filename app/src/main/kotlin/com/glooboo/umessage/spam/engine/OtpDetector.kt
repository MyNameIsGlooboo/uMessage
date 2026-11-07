package com.glooboo.umessage.spam.engine

import com.glooboo.umessage.spam.TextNormalizer

/**
 * Heuristic one-time-password detector for SMART mode. Pure JVM.
 *
 * A body looks like an OTP when a 4-8 digit code sits next to an OTP keyword: at most a few plain words
 * between them, in either order ("Your OTP is 4821", "123456 is your login code", "رمز پویا: ۱۲۳۴۵").
 * The text is read through [TextNormalizer.fold] first, so Persian and Arabic digits, Arabic letter forms (ي, ك),
 * kashida and zero-width characters do not hide a code. Word edges are explicit lookarounds over letters and
 * digits of any script, because `\b` behaves differently in the JVM and Android (ICU) regex engines.
 *
 * Marketing texts are not codes even when they say "code" next to a number ("promo code 2025", "کد تخفیف"),
 * a number that reads as a price or a percentage is never the code, and next to the generic word "code" a year
 * ("2025", "1403") is not one either, but only when the text also speaks of a year or a date ("valid until 2025"):
 * "Your Uber code is 1987" is a code. Words of discounts and prizes veto every match; words of gifts and offers only
 * the generic "code" ("Apple Gift Card verification code 482913" is a code). The word lists, in many languages, are
 * in [OtpKeywords].
 */
object OtpDetector {
    private const val EDGE_BEFORE = "(?<![\\p{L}\\p{N}])"
    private const val EDGE_AFTER = "(?![\\p{L}\\p{N}])"

    /** Keywords that say what the number is: any 4-8 digit number next to one is a code. */
    internal val strongKeywords: List<String> = OtpKeywords.strong

    /** Generic words ("code", "کد", "код"): a number next to one is a code unless it reads as a year. */
    internal val weakKeywords: List<String> = OtpKeywords.weak

    /** Persian possessive endings ("کدتان", "رمزت"); zero-width non-joiners are already gone after folding. */
    private const val SUFFIX = "(?:ی|ات|تان|ت|ش)?"

    /** The space inside a keyword is optional: Persian often writes "کد\u200Cتایید" with a ZWNJ, which folding drops. */
    internal fun keywordPattern(words: List<String>) = EDGE_BEFORE + "(?:" +
        words.map { TextNormalizer.fold(it).replace(" ", "\\s?") }.sortedByDescending { it.length }.joinToString("|") +
        ")" + SUFFIX + EDGE_AFTER

    /** A number followed by these is an amount or a percentage, not a code. */
    internal const val NOT_AFTER =
        "(?!\\s?(?:[%٪$€£]|ریال|تومان|تومن|rials?|tomans?|usd|dollars?|euros?|off)(?![\\p{L}\\p{N}]))"

    /** Words that say a number is a phone number to call or write to ("call 0800...", "تماس ..."), never a code. */
    private val phoneWords = listOf(
        "call", "tel", "phone", "whatsapp", "hotline", "dial", "تماس", "تلفن", "واتس ?اپ", "واتساپ", "شماره تماس",
    ).joinToString("|") { TextNormalizer.fold(it) }

    /**
     * What a code looks like, shared by the filter and the "Copy code" extractor ([OtpCodeExtractor]) so they cannot
     * drift apart: 4-8 digits in one run, or two groups joined by one space or hyphen ("123 456", "123-456",
     * "1234 5678"). The text must be folded ([TextNormalizer.fold]), so every digit is ASCII.
     *
     * Digits that belong to a phone number or a longer number are no code: a run after "+", a run starting with "00"
     * and 7+ digits long, a run next to another group of 3+ digits ("0800 123 4567"), and a run after a word such as
     * "call" or "tel".
     */
    internal val codeShape = "(?<!\\+)(?<!\\p{N}{3}[ -])(?<!(?:$phoneWords)[^\\p{L}\\p{N}]{0,3})(?!00[0-9]{5})" +
        "(?:[0-9]{3}[ -][0-9]{3}|[0-9]{4}[ -][0-9]{4}|[0-9]{4,8})(?![ -]\\p{N}{3})"

    private val CODE = "(?<![\\p{N}$€£])$codeShape(?!\\p{N})$NOT_AFTER"

    /** A 4-digit year, Gregorian or Persian solar (1300-1499, 1900-2099). */
    internal const val YEAR = "(?:1[34]|19|20)\\d\\d(?!\\p{N})"
    private val CODE_NOT_YEAR = "(?<![\\p{N}$€£])(?!$YEAR)$codeShape(?!\\p{N})$NOT_AFTER"

    /** Separator, then up to four plain words (letters only, so "SAVE20" or a phone number breaks the chain). */
    private const val NEAR = "[^\\p{L}\\p{N}]{1,4}(?:[\\p{L}\\p{M}]{1,15}[^\\p{L}\\p{N}]{1,4}){0,4}"

    /** A keyword that says what the number is, next to a code: a code in any case, however it looks. */
    private val strongOtp: Regex = run {
        val strong = keywordPattern(strongKeywords)
        Regex("$strong$NEAR$CODE|$CODE$NEAR$strong", RegexOption.IGNORE_CASE)
    }

    /** The generic word "code" next to a code; a year-like number does not count (see [yearContext]). */
    private val weakOtp: Regex = run {
        val weak = keywordPattern(weakKeywords)
        Regex("$weak$NEAR$CODE_NOT_YEAR|$CODE_NOT_YEAR$NEAR$weak", RegexOption.IGNORE_CASE)
    }

    /** Like [weakOtp], for a text without a word that makes a year-like number a year ("Your Uber code is 1987"). */
    private val weakOtpAnyNumber: Regex = run {
        val weak = keywordPattern(weakKeywords)
        Regex("$weak$NEAR$CODE|$CODE$NEAR$weak", RegexOption.IGNORE_CASE)
    }

    /** Languages without spaces: the keyword and the code with a few other characters in between, either order. */
    private val cjkOtp: Regex = run {
        val words = OtpKeywords.cjk.map { TextNormalizer.fold(it).replace(" ", "\\s?") }
            .sortedByDescending { it.length }
        val keyword = "(?:" + words.joinToString("|") + ")"
        val gap = "[^\\p{N}]{0,${OtpKeywords.CJK_GAP}}"
        Regex("$keyword$gap$CODE|$CODE$gap$keyword", RegexOption.IGNORE_CASE)
    }

    /** A word that says a year-like number is a year: "valid until 2025", "سال ۱۴۰۴". */
    private val yearContext = Regex(
        "(?<!\\p{L})(?:" + OtpKeywords.yearContext.joinToString("|") { TextNormalizer.fold(it) } + ")(?!\\p{L})",
        RegexOption.IGNORE_CASE,
    )

    /** Words and marks that make a text an advertisement, whatever the strong keyword says. */
    private val marketingHard = Regex(
        "(?<!\\p{L})(?:" + OtpKeywords.marketingHard.joinToString("|") { TextNormalizer.fold(it) } + ")(?!\\p{L})" +
            "|" + OtpKeywords.marketingCjk.joinToString("|") + "|\\d\\s?[%٪]",
        RegexOption.IGNORE_CASE,
    )

    /** Words of offers and gifts: they only count against a code next to the generic word "code". */
    private val marketingSoft = Regex(
        "(?<!\\p{L})(?:" + OtpKeywords.marketingSoft.joinToString("|") { TextNormalizer.fold(it) } + ")(?!\\p{L})",
        RegexOption.IGNORE_CASE,
    )

    private val tlds = OtpKeywords.tlds

    /**
     * A dot, also written to dodge link detection: "[.]", "(.)", "{.}", "[dot]", "(dot)". Every bracket is escaped:
     * Android's ICU regex engine rejects a bare "}" (and desktop Java does not), which broke this whole object on phones.
     */
    private const val DOT = "(?:\\.|\\[\\.\\]|\\(\\.\\)|\\{\\.\\}|\\[dot\\]|\\(dot\\))"
    private const val URL_LABEL = "[a-z0-9][a-z0-9\\-]*"
    /** Not glued to a Latin letter or digit; a Persian or other non-Latin letter before a domain does not hide it. */
    private const val URL_START = "(?<![a-z0-9\\u00C0-\\u024F@.\\-])"

    private val url = Regex(
        "h[xt]{2}ps?://|www$DOT" +
            // Any domain followed by a path.
            "|$URL_START$URL_LABEL(?:$DOT$URL_LABEL)*$DOT[a-z]{2,}/" +
            // A bare domain with a common top-level domain.
            "|$URL_START$URL_LABEL(?:$DOT$URL_LABEL)*$DOT(?:${tlds.joinToString("|")})(?![\\p{L}\\p{N}\\-])" +
            // A last label with letters and a hyphen or digit, with any top-level domain ("secure-bank.help").
            "|$URL_START(?:$URL_LABEL$DOT)*(?=[a-z0-9\\-]*[a-z])(?=[a-z0-9\\-]*[\\-0-9])" +
            "$URL_LABEL$DOT[a-z]{2,24}(?![\\p{L}\\p{N}\\-])" +
            // An IP address with a path or a port.
            "|(?<![\\p{N}.])\\d{1,3}(?:\\.\\d{1,3}){3}[/:]",
        RegexOption.IGNORE_CASE,
    )

    /** One DNS label: letters, digits and inner hyphens. */
    private const val LABEL = "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"
    private const val HOST = "@$LABEL(?:\\.$LABEL)+"

    /**
     * The origin-bound line of WebOTP and Android's SMS User Consent / Retriever APIs: `@host #code`, optionally followed
     * by ` @embedded-host` for a code meant for an iframe. It must be the last line of the message.
     */
    private val originBound = Regex("^$HOST #[a-z0-9]{4,12}(?: $HOST)?$", RegexOption.IGNORE_CASE)

    fun looksLikeOtp(body: String): Boolean {
        val text = TextNormalizer.fold(body)
        if (marketingHard.containsMatchIn(text)) return false
        if (strongOtp.containsMatchIn(text) || cjkOtp.containsMatchIn(text)) return true
        if (marketingSoft.containsMatchIn(text)) return false
        // Only the generic word "code": a year-like number is no code when the text speaks of a year or a date.
        return (if (yearContext.containsMatchIn(text)) weakOtp else weakOtpAnyNumber).containsMatchIn(text)
    }

    /**
     * Whether the last line binds the code to a site (`@example.com #123456`), so the browser or app only offers it there.
     * Such a line is a standard format, not a link someone is asked to tap.
     */
    fun isOriginBound(body: String): Boolean = originBound.matches(lastLine(body))

    /**
     * A link in the body, ignoring an origin-bound last line ([isOriginBound]). SMART mode does not auto-allow an
     * OTP-like message with a link (phishing), unless it is origin-bound. Bare domains ("mybank-secure.com"), short
     * links ("bit.ly") and disguised dots ("example[.]com") count as links.
     */
    fun containsUrl(body: String): Boolean {
        val text = if (isOriginBound(body)) body.trimEnd().substringBeforeLast('\n', "") else body
        return url.containsMatchIn(TextNormalizer.fold(text))
    }

    private fun lastLine(body: String): String = TextNormalizer.fold(body.trimEnd().substringAfterLast('\n').trim())

    /** Replaces every Unicode decimal digit (Persian, Arabic-Indic, ...) with its ASCII digit. */
    fun normalizeDigits(text: String): String {
        if (text.none { it.isDigit() && it !in '0'..'9' }) return text
        val out = StringBuilder(text.length)
        for (c in text) {
            val digit = if (c in '0'..'9' || !c.isDigit()) -1 else Character.digit(c, 10)
            out.append(if (digit >= 0) '0' + digit else c)
        }
        return out.toString()
    }
}

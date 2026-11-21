package com.glooboo.umessage.spam.engine

import com.glooboo.umessage.helpers.OtpCodes
import com.glooboo.umessage.spam.TextNormalizer

/**
 * Heuristic one-time-password detector for SMART mode. Pure JVM.
 *
 * A body looks like an OTP when a 4-8 digit code sits next to an OTP keyword: at most a few plain words
 * between them, in either order ("Your OTP is 4821", "123456 is your login code", "رمز پویا: ۱۲۳۴۵"), or up to eight
 * words of one sentence from a phrase that names a one-time code or a password ([WIDE_NEAR]). A letters-and-digits
 * code ("Your code is ab12cd") counts too, but only next to a keyword in its own sentence ([alphanumericCodes]).
 * The text is read through [TextNormalizer.fold] first, so Persian and Arabic digits, Arabic letter forms (ي, ك),
 * kashida and zero-width characters do not hide a code. Word edges are explicit lookarounds over letters and
 * digits of any script, because `\b` behaves differently in the JVM and Android (ICU) regex engines. A separator
 * never holds a combining mark (`\p{M}`), and runs are possessive, so no pattern can cut one word in many ways: every
 * pattern stays linear, also for Thai, Hindi or a run of accents. Only the first [MAX_SCAN] characters are read.
 *
 * Marketing texts are not codes even when they say "code" next to a number ("promo code 2025", "کد تخفیف"),
 * a number that reads as a price or a percentage is never the code, and next to the generic word "code" a year
 * ("2025", "1403") is not one either, but only when the text also speaks of a year or a date ("valid until 2025"):
 * "Your Uber code is 1987" is a code. Words of discounts and prizes veto every match, except a code right next to a
 * phrase of [OtpKeywords.codePhrases] when the discount word only comes after the code's sentence (an advertising
 * footer; never a prize word, [OtpKeywords.prize]); words of gifts
 * and offers only the generic "code" ("Apple Gift Card verification code 482913" is a code). The word lists, in many
 * languages, are in [OtpKeywords].
 */
@Suppress("TooManyFunctions") // One small reading per question the app asks; each shares the patterns above it.
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

    /** Characters that mask a card or account number: "****1234", "••••1234", "#### 1234". */
    private const val MASK = "[*\u2022\u25CF#\u00D7]"

    /**
     * Checked at the end of a number: a masked number is no code. That is a number with two mask characters, or a digit
     * and one, before it ("****1234", "**** 1234") unless a mask character also closes it ("**482913**" is bold), and a
     * number followed by two mask characters ("1234****") unless one opens it. A single star is no mask ("*482913*").
     */
    private const val MASK_GUARD = "(?:(?=$MASK)|(?<!(?:\\p{N}|$MASK)$MASK[0-9 -]{4,10}))" +
        "(?:(?!$MASK{2})|(?<=$MASK[0-9 -]{4,10}))"

    /**
     * Characters that join digits into one bigger number: a date, a time, an amount ("1405/08/27", "14:32"). A hyphen
     * is not one of them, as a code is also written in groups ("4829-13"); a date written with hyphens is [HYPHEN_DATE].
     */
    private const val JOINERS = "[/:.,\u066B\u066C]"

    /** A number that starts or ends a date written with hyphens ("2026-11-18", "18-11-2026"). */
    private const val HYPHEN_DATE_AFTER = "(?!-\\p{N}{1,2}-\\p{N})"
    private const val HYPHEN_DATE_BEFORE = "(?<!\\p{N}-\\p{N}{1,2}-)"

    /**
     * Words that name what a number right after them is: a number, an ID, a balance, an account ("به شماره 1234567",
     * "شناسه 12345678", "مانده 250000", "tracking number is 48291375", "account ending in 482913"). Such a number is no
     * code. A bounded look-behind, so Android's ICU engine accepts it.
     */
    private const val REFERENCE_BEFORE = "(?<!(?:شماره|شناسه|مانده حساب|مانده|مبلغ|tracking number|account number|" +
        "order number|reference number|account|balance|amount|ending in)(?:\\sis)?[^\\p{L}\\p{M}\\p{N}]{1,3})"

    /** Not after a digit, a currency sign or a reference word, not one part of a date or amount, not masked. */
    private const val EDGES_BEFORE =
        "(?=[0-9])(?<![\\p{N}$€£])(?<!\\p{N}$JOINERS)$HYPHEN_DATE_BEFORE$REFERENCE_BEFORE"
    private const val EDGES_AFTER = "(?!\\p{N})(?!$JOINERS\\p{N})$HYPHEN_DATE_AFTER$MASK_GUARD$NOT_AFTER"

    private val CODE = "$EDGES_BEFORE$codeShape$EDGES_AFTER"

    /** A 4-digit year, Gregorian or Persian solar (1300-1499, 1900-2099). */
    internal const val YEAR = "(?:1[34]|19|20)\\d\\d(?!\\p{N})"
    private val CODE_NOT_YEAR = "$EDGES_BEFORE(?!$YEAR)$codeShape$EDGES_AFTER"

    /** A date written as eight digits, Gregorian or Persian solar ("20261231", "14050827"). */
    private const val DATE = "(?:1[34]|19|20)\\d\\d(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])(?!\\p{N})"

    /** A code far from its phrase ([WIDE_NEAR]): no year and no date. */
    private val CODE_FAR = "$EDGES_BEFORE(?!$YEAR)(?!$DATE)$codeShape$EDGES_AFTER"

    /** Digits of the longest code of [codeShape]; a longer number is a code only next to a password word. */
    internal const val MAX_CODE_DIGITS = 8

    /**
     * A 9-10 digit number, a first password ("رمز شخصی شما 1234567890"). Never a number that starts with 0 or 98 (a phone
     * number), or follows "+" or a phone word. It is a code only next to a password word ([passwordKeywords]).
     */
    internal val longShape = "(?<!\\+)(?<!(?:$phoneWords)[^\\p{L}\\p{N}]{0,3})(?!0|98)[0-9]{9,10}"

    private val LONG_CODE = "$EDGES_BEFORE$longShape$EDGES_AFTER"

    /** Words that say a number is a password: the only words that make a long number a code. */
    internal val passwordKeywords: List<String> = OtpKeywords.password

    private val passwordOtp: Regex = run {
        val password = keywordPattern(passwordKeywords)
        Regex("$password$NEAR$LONG_CODE|$LONG_CODE$NEAR$password", RegexOption.IGNORE_CASE)
    }

    /**
     * Separator, then up to four plain words (letters only, so "SAVE20" or a phone number breaks the chain). A
     * separator is up to eight spaces or marks, so "Code:" and the number two blank lines further down still meet.
     * Neither a separator nor a word is ever cut back (possessive), and a combining mark belongs to the word only.
     */
    internal const val NEAR =
        "[^\\p{L}\\p{M}\\p{N}]{1,8}+(?:[\\p{L}\\p{M}]{1,15}+[^\\p{L}\\p{M}\\p{N}]{1,8}+){0,4}"

    /** A separator that stays inside one sentence: no full stop, mark or line break ([sentenceBreaks]). */
    private const val IN_SENTENCE = "[^\\p{L}\\p{M}\\p{N}.!?;\\n\\r\u061F\u06D4]{1,4}+"

    /**
     * What else may stand between a phrase that names a one-time code or a password ([wideWords]) and the code: up to
     * eight words of one sentence, each starting with a letter and up to 20 characters long, digits allowed ("Shop24"),
     * as in "رمز یکبار مصرف شما برای پرداخت قبض در درگاه بانک نمونه ۴۸۲۹۱۳ است". A number of its own breaks the chain,
     * and a year or a date this far from the phrase is no code ("Your password expires at the end of December 2026").
     */
    private const val WIDE_NEAR =
        "$IN_SENTENCE(?:[\\p{L}\\p{M}][\\p{L}\\p{M}\\p{N}]{0,19}+$IN_SENTENCE){0,8}"

    /** The only words with the wide reach: not "verify", "log in" or "تایید", whose texts are often bank notices. */
    private val wideWords = keywordPattern(OtpKeywords.codePhrases + OtpKeywords.password)

    /** A keyword that says what the number is, next to a code: a code in any case, however it looks. */
    private val strongOtp: Regex = run {
        val strong = keywordPattern(strongKeywords)
        Regex(
            "$strong$NEAR$CODE|$CODE$NEAR$strong|$wideWords$WIDE_NEAR$CODE_FAR|$CODE_FAR$WIDE_NEAR$wideWords",
            RegexOption.IGNORE_CASE,
        )
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

    /**
     * A letters-and-digits code that no digit rule sees ("ab12cd", "x7kq2m", "12ab34"): 4-8 letters and digits in one
     * run, at least two of each, letters and digits alternating at least twice. Deliberately narrow, as it only counts
     * next to a keyword in the same sentence ([alphanumericCodes]): a promo code ("SAVE20": letters, then digits),
     * a time or unit ("10am", "500mb"), an ordinal ("3rd"), a word with one digit ("mp3s") and a run holding four
     * digits in a row (the digit rule's business: "G-482915" is copied as 482915) are no match. Not glued to a
     * letter, a digit or the characters of an address, a path or a price.
     */
    private val alphanumericCode = Regex(
        "(?<![\\p{L}\\p{N}@./\\\\_+*#\\-\$€£])" +
            "(?=[0-9a-z]{4,8}(?![\\p{L}\\p{N}]))(?=(?:[a-z]*[0-9]){2})(?=(?:[0-9]*[a-z]){2})" +
            "(?![0-9a-z]*[0-9]{4})(?![0-9]{1,2}h[0-9]{2})" +
            "(?:[a-z]+[0-9]+[a-z][0-9a-z]*|[0-9]+[a-z]+[0-9][0-9a-z]*)" +
            "(?![\\p{L}\\p{N}@/\\\\_])(?![.\\-][\\p{L}\\p{N}])" + NOT_AFTER,
        RegexOption.IGNORE_CASE,
    )

    /** A full stop, mark or line break ends the sentence a keyword has to share with an alphanumeric code. */
    private val sentenceBreaks = charArrayOf('.', '!', '?', '\n', '\r', ';', '؟', '۔')

    /** Words and marks that make a text an advertisement, whatever the strong keyword says. */
    private val marketingHard = Regex(
        "(?<!\\p{L})(?:" + OtpKeywords.marketingHard.joinToString("|") { TextNormalizer.fold(it) } + ")(?!\\p{L})" +
            "|" + OtpKeywords.marketingCjk.joinToString("|") + "|\\d\\s?[%٪]",
        RegexOption.IGNORE_CASE,
    )

    /**
     * The Persian opt-out line of advertisements ("لغو۱۱"). It vetoes a code that has no strong keyword next to it; a
     * strong keyword ("کد ورود", "فرم ورود") wins over it, as a few real codes carry the line too.
     */
    private val marketingOptOut = Regex(
        "(?<!\\p{L})(?:" + OtpKeywords.optOut.joinToString("|") { TextNormalizer.fold(it) } + ")(?!\\p{L})",
        RegexOption.IGNORE_CASE,
    )

    /** A phrase that only a one-time code carries, next to a code ([OtpKeywords.codePhrases]). */
    private val codePhraseOtp: Regex = run {
        val phrase = keywordPattern(OtpKeywords.codePhrases)
        Regex("$phrase$NEAR$CODE|$CODE$NEAR$phrase", RegexOption.IGNORE_CASE)
    }

    /** Words of prizes ([OtpKeywords.prize]): unlike the other words of [marketingHard], no footer. */
    private val prize = Regex(
        "(?<!\\p{L})(?:" + OtpKeywords.prize.joinToString("|") { TextNormalizer.fold(it) } + ")(?!\\p{L})",
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

    /**
     * Whether [body] holds a verification code. The one rule of the app: the spam filter, forwarding, the notification,
     * the list and chat chips, the Codes filter, the digest and the category all ask this question through
     * [OtpCodes.looksLikeCode], which also accepts labelled values ("password: x7Kq2").
     */
    fun looksLikeOtp(body: String): Boolean = OtpCodes.looksLikeCode(body)

    /** The characters either side of a code that are searched for a keyword. */
    private const val WINDOW = 120

    /** How much of a text is read for a code: a longer text (an MMS, a pasted page) has its code near the start. */
    internal const val MAX_SCAN = 2000

    private val strongBefore = Regex(keywordPattern(strongKeywords) + NEAR + "$", RegexOption.IGNORE_CASE)
    private val strongAfter = Regex("^" + NEAR + keywordPattern(strongKeywords), RegexOption.IGNORE_CASE)
    private val wideBefore = Regex(wideWords + WIDE_NEAR + "$", RegexOption.IGNORE_CASE)
    private val wideAfter = Regex("^" + WIDE_NEAR + wideWords, RegexOption.IGNORE_CASE)
    private val weakBefore = Regex(keywordPattern(weakKeywords) + NEAR + "$", RegexOption.IGNORE_CASE)
    private val weakAfter = Regex("^" + NEAR + keywordPattern(weakKeywords), RegexOption.IGNORE_CASE)
    private val cjkWords = "(?:" + OtpKeywords.cjk.map { TextNormalizer.fold(it).replace(" ", "\\s?") }
        .sortedByDescending { it.length }.joinToString("|") + ")"
    private val cjkBefore = Regex(cjkWords + "[^\\p{N}]{0,${OtpKeywords.CJK_GAP}}$", RegexOption.IGNORE_CASE)
    private val cjkAfter = Regex("^[^\\p{N}]{0,${OtpKeywords.CJK_GAP}}" + cjkWords, RegexOption.IGNORE_CASE)
    private val yearOnly = Regex(YEAR)
    private val farCode = Regex("(?!$YEAR)(?!$DATE)[0-9 -]+")

    /**
     * Whether the code at [range] of [text] (already folded) has a keyword a few plain words before or after it, on
     * the same terms as [nearKeyword] (no advertisement; next to the generic word only when it is no year). Several
     * codes of one message ("Use 333333 to log in, or 444444 to reset") each qualify on their own.
     */
    internal fun keywordNextTo(text: String, range: IntRange, sameSentence: Boolean = false): Boolean {
        if (marketingHard.containsMatchIn(text)) return !sameSentence && codeNextToPhrase(text, range)
        if (OtpPasswords.isLong(text.substring(range))) return OtpPasswords.nextTo(text, range, WINDOW)
        var before = text.substring(maxOf(0, range.first - WINDOW), range.first)
        var after = text.substring(range.last + 1, minOf(text.length, range.last + 1 + WINDOW))
        if (sameSentence) {
            before = before.substring(before.lastIndexOfAny(sentenceBreaks) + 1)
            val end = after.indexOfAny(sentenceBreaks)
            if (end >= 0) after = after.substring(0, end)
        }
        val value = text.substring(range)
        if (strongNextTo(before, after, farCode.matches(value))) return true
        if (marketingOptOut.containsMatchIn(text) || marketingSoft.containsMatchIn(text)) return false
        val year = yearOnly.matches(value) && yearContext.containsMatchIn(text)
        return !year && (weakBefore.containsMatchIn(before) || weakAfter.containsMatchIn(after))
    }

    /**
     * Whether a keyword that says what the number is stands in [before] or [after] a code: a few plain words away, up
     * to eight words of the sentence away from a [wideWords] phrase when the code is [far] (no year, no date), or a
     * CJK word.
     */
    private fun strongNextTo(before: String, after: String, far: Boolean): Boolean =
        strongBefore.containsMatchIn(before) || strongAfter.containsMatchIn(after) ||
            far && (wideBefore.containsMatchIn(before) || wideAfter.containsMatchIn(after)) ||
            cjkBefore.containsMatchIn(before) || cjkAfter.containsMatchIn(after)

    /**
     * Whether the code at [range] of [text] (folded) has a keyword a few plain words before or after it, the plain
     * word "code" included, but not the wide reach: what a "do not share" line may vouch for ([AllowingCode]).
     */
    internal fun keywordClose(text: String, range: IntRange): Boolean {
        val before = text.substring(maxOf(0, range.first - WINDOW), range.first)
        val after = text.substring(range.last + 1, minOf(text.length, range.last + 1 + WINDOW))
        return strongNextTo(before, after, far = false) ||
            weakBefore.containsMatchIn(before) || weakAfter.containsMatchIn(after)
    }

    /**
     * Whether a code of [text] (folded), the one at [range] if given, stands next to a phrase of
     * [OtpKeywords.codePhrases] while the text holds no prize word and no word of [marketingHard] up to the end of the
     * code's sentence: a real code with an advertising footer.
     */
    private fun codeNextToPhrase(text: String, range: IntRange? = null): Boolean =
        !prize.containsMatchIn(text) && codePhraseOtp.findAll(text).any {
            val holdsCode = range == null || range.first >= it.range.first && range.last <= it.range.last
            holdsCode && !marketingHard.containsMatchIn(text.substring(0, sentenceEnd(text, it.range.last + 1)))
        }

    /**
     * Where the sentence that goes on at [from] ends: at a mark or line break of [sentenceBreaks], but a full stop
     * only when a space or the end of the text follows it, so a link ("shop.example.com") or a version ("v2.0") does
     * not end it.
     */
    private fun sentenceEnd(text: String, from: Int): Int {
        for (i in from until text.length) {
            val dotGoesOn = text[i] == '.' && i + 1 < text.length && !text[i + 1].isWhitespace()
            if (text[i] in sentenceBreaks && !dotGoesOn) return i
        }
        return text.length
    }

    /**
     * Whether the folded or raw [body] has no word of an advertisement before its code: no word of [marketingHard], or
     * only an advertising footer after a code phrase ([codeNextToPhrase]). A labelled password in an advertisement
     * ("Winner! password: 12345678") fails it. The old filter (flag off) lets a code through only with this.
     */
    internal fun noAdBeforeCode(body: String): Boolean = TextNormalizer.fold(body).take(MAX_SCAN).let {
        !marketingHard.containsMatchIn(it) || codeNextToPhrase(it)
    }

    /**
     * The letters-and-digits codes of [text] (already folded) with a keyword in the same sentence, a few plain words
     * before or after them: position and value, as written ("Your code is ab12cd" gives ab12cd). Never bare: a token
     * of this shape without a keyword, in an advertisement, or next to the generic word in a text of offers is no code.
     */
    internal fun alphanumericCodes(text: String): List<Pair<Int, String>> {
        if (marketingHard.containsMatchIn(text)) return emptyList()
        return alphanumericCode.findAll(text)
            .filter { keywordNextTo(text, it.range, sameSentence = true) }
            .map { it.range.first to it.value }
            .toList()
    }

    /**
     * Whether the folded [text] holds a word of [marketingHard] ("promo", "discount", "50%") anywhere. Such a text never
     * allows its sender ([AllowingCode]), even when it shows a code next to a phrase of [OtpKeywords.codePhrases].
     */
    internal fun hasAdvertisingWord(text: String): Boolean = marketingHard.containsMatchIn(text)

    /** Whether [text] is an advertisement ("promo", "discount", "50%"): such a text is never a code. */
    internal fun isMarketing(text: String): Boolean = TextNormalizer.fold(text).let {
        marketingHard.containsMatchIn(it) || marketingOptOut.containsMatchIn(it)
    }

    /**
     * The keyword rule behind [looksLikeOtp]: a 4-8 digit code, or a letters-and-digits one ([alphanumericCodes]), next
     * to an OTP keyword (a few plain words apart, either order), in a text that is not an advertisement. Labelled values are [OtpCodes]' business.
     */
    internal fun nearKeyword(body: String): Boolean {
        val text = TextNormalizer.fold(body).take(MAX_SCAN)
        return when {
            // Every code of this rule holds a digit (folding made them ASCII): a text without one is read no further.
            text.none { it in '0'..'9' } -> false
            marketingHard.containsMatchIn(text) -> codeNextToPhrase(text)
            strongOtp.containsMatchIn(text) || cjkOtp.containsMatchIn(text) -> true
            passwordOtp.containsMatchIn(text) -> true
            alphanumericCodes(text).isNotEmpty() -> true
            marketingOptOut.containsMatchIn(text) -> false
            marketingSoft.containsMatchIn(text) -> false
            // Only the generic word "code": a year-like number is no code when the text speaks of a year or a date.
            else -> (if (yearContext.containsMatchIn(text)) weakOtp else weakOtpAnyNumber).containsMatchIn(text)
        }
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

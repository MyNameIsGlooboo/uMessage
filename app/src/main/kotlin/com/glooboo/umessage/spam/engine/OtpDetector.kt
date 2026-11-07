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
 * ("2025", "1403") is not one either.
 */
object OtpDetector {
    private const val EDGE_BEFORE = "(?<![\\p{L}\\p{N}])"
    private const val EDGE_AFTER = "(?![\\p{L}\\p{N}])"

    /** Keywords that say what the number is: any 4-8 digit number next to one is a code. */
    internal val strongKeywords = listOf(
        // English
        "otp", "passcode", "password", "pin", "verification", "verify", "one[- ]?time", "authenticat\\p{L}*",
        "security code", "login code", "2fa",
        // Persian
        "رمز", "رمز پویا", "رمز عبور", "رمز یکبار مصرف", "گذرواژه", "کد تایید", "کد تأیید", "کد فعالسازی",
        "کد ورود", "تایید", "تأیید", "تائید", "یکبار مصرف", "یک بار مصرف",
        // Arabic (written with Persian yeh and kaf, as the text is folded the same way)
        "الرمز", "رمز التحقق", "رمز التفعیل", "کلمة المرور", "التحقق", "التفعیل",
    )

    /** Generic words ("code", "کد"): a number next to one is a code unless it reads as a year. */
    internal val weakKeywords = listOf("code", "کد", "کود")

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
    private const val NEAR = "[^\\p{L}\\p{N}]{1,4}(?:\\p{L}{1,15}[^\\p{L}\\p{N}]{1,4}){0,4}"

    private val otp: Regex = run {
        val strong = keywordPattern(strongKeywords)
        val weak = keywordPattern(weakKeywords)
        Regex(
            "$strong$NEAR$CODE|$CODE$NEAR$strong|$weak$NEAR$CODE_NOT_YEAR|$CODE_NOT_YEAR$NEAR$weak",
            RegexOption.IGNORE_CASE,
        )
    }

    /**
     * Words of advertising, prizes and offers. A message with one of them is not treated as a code: real codes do not
     * come with a discount, and "promo code 2025" must not reach the inbox as a login code.
     */
    private val marketingWords = listOf(
        // English
        "promo", "promotion", "promotional", "coupon", "voucher", "discount", "sale", "cashback", "offer", "prize",
        "lottery", "reward", "rewards", "gift", "bonus", "jackpot", "winner",
        // Persian ("لغو۱۱" is the opt-out line of local ads)
        "تخفیف", "جشنواره", "حراج", "جایزه", "برنده", "قرعه کشی", "قرعه\u200Cکشی", "هدیه", "رایگان", "پیشنهاد ویژه",
        "کوپن", "لغو ?11", "تبلیغ",
        // Arabic
        "خصم", "جائزة", "هدیة", "مجانا", "کوبون",
    )

    private val marketing = Regex(
        "(?<!\\p{L})(?:" + marketingWords.map(TextNormalizer::fold).joinToString("|") + ")(?!\\p{L})|\\d\\s?[%٪]",
        RegexOption.IGNORE_CASE,
    )

    /** Top-level domains a bare domain ("mybank-secure.com", "x.ir") is recognised by, without http or a slash. */
    private val tlds = listOf(
        "com", "net", "org", "ir", "info", "biz", "io", "co", "app", "xyz", "site", "online", "top", "link", "click",
        "shop", "store", "live", "ly", "gl", "gd", "cc", "tk", "ml", "ga", "cf", "gq", "ru", "cn", "uk", "de", "eu",
        "tv", "ai", "dev", "pro", "club", "vip", "icu", "cyou", "sbs", "buzz", "rest", "fun", "space", "website", "tech",
        "ws", "su", "pw", "lol", "ae", "tr",
    )

    /**
     * A dot, also written to dodge link detection: "[.]", "(.)", "{.}", "[dot]", "(dot)". Every bracket is escaped:
     * Android's ICU regex engine rejects a bare "}" (and desktop Java does not), which broke this whole object on phones.
     */
    private const val DOT = "(?:\\.|\\[\\.\\]|\\(\\.\\)|\\{\\.\\}|\\[dot\\]|\\(dot\\))"
    private const val URL_LABEL = "[a-z0-9][a-z0-9\\-]*"
    private const val URL_START = "(?<![\\p{L}\\p{N}@.\\-])"

    private val url = Regex(
        "h[xt]{2}ps?://|www$DOT" +
            // Any domain followed by a path.
            "|$URL_START$URL_LABEL(?:$DOT$URL_LABEL)*$DOT[a-z]{2,}/" +
            // A bare domain with a common top-level domain.
            "|$URL_START$URL_LABEL(?:$DOT$URL_LABEL)*$DOT(?:${tlds.joinToString("|")})(?![\\p{L}\\p{N}\\-])" +
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
        return otp.containsMatchIn(text) && !marketing.containsMatchIn(text)
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

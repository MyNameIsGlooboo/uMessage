package com.glooboo.umessage.ui.thread

/* Phone numbers written in a message text: found for tappable links, kept strict so codes, prices and dates are not. */

/** A phone number found in a text: [range] is where it is written, [number] is what to dial ("+989121234567"). */
internal data class PhoneMatch(val range: IntRange, val number: String)

private const val TEL = "tel:"
private const val MIN_DIGITS = 7
private const val MAX_DIGITS = 15
private const val DECIMAL_RADIX = 10

/** Fewest digits of a number written as one run without a "+" or a leading 0: shorter runs are mostly codes. */
private const val MIN_PLAIN_RUN_DIGITS = 10

/**
 * Letters, digits and signs that, right before a number, mean it is part of something else (a price, a path, a
 * word).
 */
private const val NOT_BEFORE = "\\p{L}\\p{N}_@/\\\\.,$€£¥#%*+-"

private val candidate = Regex(
    "(?<![$NOT_BEFORE])" +
        "(?:\\+|00)?[(]?\\p{Nd}[\\p{Nd} ().\\-\\u00A0]{5,}\\p{Nd}" +
        "(?![\\p{L}\\p{N}_@/%]|[.,]\\p{N})",
)
private val dateLike = Regex("^\\d{1,4}([-./])\\d{1,2}\\1\\d{1,4}$")
/** Digits joined only by dots: a price, a version or a date unless it has a leading 0 or "+" or is 3.3.4 (US style). */
private val dottedOnly = Regex("^\\d+(\\.\\d+)+$")
private val dottedPhone = Regex("^\\d{3}\\.\\d{3}\\.\\d{4}$")

/** A decimal amount such as "1234567.50". */
private val decimalTail = Regex("^\\d+\\.\\d{1,2}$")

/** [value] with Persian and Arabic-Indic digits turned into 0-9 (one char each, so positions stay). */
private fun asciiDigits(value: String): String =
    value.map {
        if (it.isDigit()) Character.digit(it, DECIMAL_RADIX).let { d -> if (d >= 0) '0' + d else it } else it
    }
        .joinToString("")

/** True for "+" or "00" (international) and for a leading 0 (national): a code or price never starts like that. */
private fun hasPhonePrefix(shown: String): Boolean =
    shown.startsWith("0") || shown.startsWith("+")

/** The digit count, and not a date, a dotted price or version, or a decimal amount. */
private fun isNumberShaped(shown: String, digits: String): Boolean {
    val local = hasPhonePrefix(shown)
    return digits.length in MIN_DIGITS..MAX_DIGITS &&
        !dateLike.matches(shown) &&
        !(dottedOnly.matches(shown) && !local && !dottedPhone.matches(shown)) &&
        !decimalTail.matches(shown)
}

/** Not a short plain run (a code), and no doubled separators or unbalanced parentheses. */
private fun isCleanRun(shown: String, digits: String): Boolean {
    val plainRun = shown.all { it in '0'..'9' || it == '+' }
    return !(plainRun && !hasPhonePrefix(shown) && digits.length < MIN_PLAIN_RUN_DIGITS) &&
        !shown.contains("..") && !shown.contains("--") &&
        !hasUnbalancedParens(shown)
}

/**
 * The phone numbers in [text], in order. Strict on purpose: 7 to 15 digits, no verification codes (short runs, or a
 * run of 7 to 9 digits with no "+" or leading 0), no prices ("1.234.567", "$1234567", "1234567.50"), version
 * numbers, no dates ("2026-11-11", "11.11.2026") and nothing inside a word, an address or a path.
 */
internal fun findPhoneNumbers(text: String): List<PhoneMatch> {
    if (text.length < MIN_DIGITS) return emptyList()
    return candidate.findAll(text).mapNotNull { match ->
        val shown = asciiDigits(match.value).trim()
        val digits = shown.filter { it in '0'..'9' }
        if (!isNumberShaped(shown, digits) || !isCleanRun(shown, digits)) return@mapNotNull null
        val dial = when {
            shown.startsWith("00") -> "+" + digits.drop(2)
            shown.startsWith("+") -> "+$digits"
            else -> digits
        }
        PhoneMatch(match.range.first..match.range.last, dial)
    }.toList()
}

private fun hasUnbalancedParens(value: String): Boolean = value.count { it == '(' } != value.count { it == ')' }

/** True for the "tel:" links [findPhoneNumbers] makes. */
internal fun isPhoneLink(uri: String): Boolean = uri.startsWith(TEL, ignoreCase = true)

/** The number of a "tel:" link ("+989121234567"), else null. */
internal fun phoneOf(uri: String): String? = if (isPhoneLink(uri)) uri.substring(TEL.length).ifBlank { null } else null

/** The "tel:" link for [number]. */
internal fun phoneLink(number: String): String = TEL + number

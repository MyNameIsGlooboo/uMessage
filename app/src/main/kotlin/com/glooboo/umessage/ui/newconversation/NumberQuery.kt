package com.glooboo.umessage.ui.newconversation

/** Characters people type inside a phone number besides digits: spaces, dashes, dots, brackets. */
private val NUMBER_PUNCTUATION = setOf(' ', '-', '.', '(', ')', ' ', '‌', '‎', '‏')

/** Every Unicode decimal digit (Persian, Arabic-Indic...) as its ASCII digit; other characters stay. */
internal fun asciiDigits(text: String): String {
    if (text.none { it.isDigit() && it !in '0'..'9' }) return text
    return buildString(text.length) {
        for (c in text) {
            val digit = if (c in '0'..'9' || !c.isDigit()) -1 else Character.digit(c, 10)
            append(if (digit >= 0) '0' + digit else c)
        }
    }
}

/**
 * MC-08: what the user typed in the To field, as a number the phone can text: digits in ASCII (a Persian keyboard
 * types ۰۵۵۵...), spacing and dashes dropped, a leading + kept. Null when it isn't a number (a name, a short code with
 * letters, an email address), so it is sent on as typed.
 */
internal fun typedPhoneNumber(text: String): String? {
    val value = asciiDigits(text.trim())
    if (value.isEmpty() || value.none { it in '0'..'9' }) return null
    val body = value.removePrefix("+")
    if (body.any { it !in '0'..'9' && it !in NUMBER_PUNCTUATION }) return null
    val digits = body.filter { it in '0'..'9' }
    return if (value.startsWith("+")) "+$digits" else digits
}

/** ID-47: the phone number on the clipboard, or null when the text is not one (or too short to be one). */
internal fun clipboardNumber(text: CharSequence?): String? =
    typedPhoneNumber(text?.toString().orEmpty())?.takeIf { it.count { c -> c in '0'..'9' } >= 3 }

/**
 * MC-09: forms of a typed number to look for in a contact's numbers, all digits only. A national "0555 345" is also
 * looked for without its trunk 0 (matching +1555345...), and an international "+1555" or "001555" also without a
 * country code of one to three digits (matching 0555...). Null when [query] is not a number.
 */
internal fun numberSearchForms(query: String): List<String>? {
    val typed = typedPhoneNumber(query) ?: return null
    val international = typed.startsWith("+") || typed.startsWith("00")
    val digits = typed.removePrefix("+").let { if (it.startsWith("00")) it.drop(2) else it }
    if (digits.isEmpty()) return null
    val forms = mutableListOf(digits)
    if (international) {
        for (codeLength in 1..3) {
            digits.drop(codeLength).takeIf { it.length >= MIN_NATIONAL_PART }?.let { forms += it }
        }
    } else if (digits.startsWith("0") && digits.length > 1) {
        forms += digits.drop(1)
    }
    return forms.distinct()
}

/** True when a contact's [number] contains one of [forms] (from [numberSearchForms]); the trunk 0 is ignored too. */
internal fun numberMatches(number: String, forms: List<String>): Boolean {
    val digits = asciiDigits(number).filter { it in '0'..'9' }
    if (digits.isEmpty()) return false
    val national = digits.trimStart('0')
    return forms.any { digits.contains(it) || national.contains(it) }
}

/** Shortest national part tried after dropping a guessed country code, so "+1" alone doesn't match everyone. */
private const val MIN_NATIONAL_PART = 3

package com.glooboo.umessage.ui.components

/** Left-to-right isolate: the run inside keeps its own order whatever the paragraph direction is. */
private const val LRI = '⁦'

/** Pop directional isolate, closing [LRI]. */
private const val PDI = '⁩'

/**
 * A phone number or an amount as people write it: an optional +, digits in any script, spaces, dashes, dots and
 * brackets. B1: a group separator (`,` `٬` `٫` `'`) counts too when a digit follows it at once, so "۱,۲۵۰,۰۰۰" is one
 * run; split into one isolate per digit group, a right-to-left line laid the groups out backwards ("۰۰۰,۲۵۰,۱").
 */
private val NUMBER_RUN = Regex("""\+?\(?\p{Nd}(?:[\p{Nd} ().\-]|[,٬٫'](?=\p{Nd}))*\p{Nd}\)?""")

/** Fewer digits than this is a count or a time, not a number to protect. */
private const val MIN_DIGITS = 3

/**
 * MC-24: wraps every phone number (and, B1, every grouped amount) in [text] in a left-to-right isolate
 * (U+2066 ... U+2069). In the Persian (right-to-left) UI a bare "+1 555 000 1234" was laid out as
 * "912 345 6789 98+"; isolated, it reads as written and still sits at the start of the right-to-left line. Names and
 * other text are left alone; text that already has isolates is returned as is.
 */
fun isolateNumbers(text: String): String {
    if (text.isEmpty() || text.none { it.isDigit() } || LRI in text) return text
    return NUMBER_RUN.replace(text) { match ->
        val value = match.value
        if (value.count { it.isDigit() } < MIN_DIGITS) value else "$LRI$value$PDI"
    }
}

package com.glooboo.umessage.ui.components

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import com.glooboo.umessage.spam.RuleType
import java.util.Locale

/** Left-to-right isolate: the run inside keeps its own order whatever the paragraph direction is. */
private const val LRI = '⁦'

/** Pop directional isolate, closing [LRI]. */
private const val PDI = '⁩'

/**
 * A phone number or an amount as people write it: an optional +, digits in any script, spaces, dashes, dots and
 * brackets. B1: a group separator (`,` `٬` `٫` `'`) counts too when a digit follows it at once, so "۱,۲۵۰,۰۰۰" is one
 * run; split into one isolate per digit group, a right-to-left line laid the groups out backwards ("۰۰۰,۲۵۰,۱").
 * G-1: the left-to-right marks that `displayNumber` puts between Arabic-Indic groups belong to the run too.
 */
private val NUMBER_RUN = Regex("""\+?\(?\p{Nd}(?:[\p{Nd} ().\-\u200E]|[,٬٫'](?=\p{Nd}))*\p{Nd}\)?""")

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

/**
 * Wraps the whole of [text] in one left-to-right isolate, for a value that is always read left to right whatever its
 * length, such as a block prefix rule ("+44", "+1"). [isolateNumbers] skips runs of fewer than three digits, so a
 * short prefix showed as "44+" in a right-to-left line. Empty text and text that already has isolates are returned
 * as is.
 */
fun isolateLtr(text: String): String = if (text.isEmpty() || LRI in text) text else "$LRI$text$PDI"

/**
 * P-1 to P-5: a rule's value as a screen shows it, so each screen does not choose by itself. A number rule shows
 * [number] (the value as `displayNumber` gives it: grouped, in local digits) with its digits isolated. A prefix is
 * written in the digits of [locale], never grouped, and isolated whole whatever its length ("+1" keeps its + in front
 * in a right-to-left line). Other types are shown as they are: isolating the digits of a Latin name such as
 * "AX-650012" would move them to the wrong side of it (F-4).
 */
fun ruleValueText(type: RuleType, value: String, locale: Locale, number: String = value): String = when (type) {
    RuleType.NUMBER -> isolateNumbers(number)
    RuleType.PREFIX -> isolateLtr(localizeDigits(value, locale))
    else -> value
}

/** F-5: a number or a prefix rule, whose value is typed and read left to right in every language. */
val RuleType.readsLeftToRight: Boolean get() = this == RuleType.NUMBER || this == RuleType.PREFIX

/** [ruleValueText] with the app's language and the phone's number grouping. */
fun ruleValueText(context: Context, type: RuleType, value: String): String = ruleValueText(
    type, value, context.uiLocale, if (type == RuleType.NUMBER) displayNumber(context, value) else value,
)

/** [ruleValueText] for composables; follows the app's language. */
@Composable
@ReadOnlyComposable
fun ruleValueText(type: RuleType, value: String): String =
    ruleValueText(type, value, uiLocale(), if (type == RuleType.NUMBER) displayNumber(value) else value)

/**
 * A rule's label where the rule may be gone (the decision log, a message's details). With the rule's [type] it is
 * [ruleValueText]. Without it, a bare number is shown as a prefix is (local digits, not grouped, isolated whole), so a
 * prefix looks the same before and after its rule is deleted (F-6); any other label ("#12", a word) as it is.
 */
fun ruleLabelText(label: String, type: RuleType?, locale: Locale, number: String = label): String = when {
    type != null -> ruleValueText(type, label, locale, number)
    isBareNumber(label) -> isolateLtr(localizeDigits(label.trim(), locale))
    else -> label
}

/** [ruleLabelText] with the app's language and the phone's number grouping. */
fun ruleLabelText(context: Context, label: String, type: RuleType?): String = ruleLabelText(
    label, type, context.uiLocale, if (type == RuleType.NUMBER) displayNumber(context, label) else label,
)

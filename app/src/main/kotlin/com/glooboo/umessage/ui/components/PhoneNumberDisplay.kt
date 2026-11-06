package com.glooboo.umessage.ui.components

import android.content.Context
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/*
 * CL-10, CL-33, TH-29, VS-38: the one way a phone number, or a sender that is only a number, is shown on screen: the
 * conversation list, the Archived tab, search, the thread header and Details all go through [displayNumber]. A bare
 * number is grouped the way the phone's country writes it ("985-0001", "0555 345 6789") and written in the digits of
 * the app's language, so the Persian UI no longer mixes "۲ نوامبر" with "5550101". Names, alphanumeric senders and
 * message text are returned untouched. Display only: copying, dialling and matching keep the stored number.
 *
 * The result is not isolated; put it through [isolateNumbers] where it sits in a line of right-to-left text.
 */

/** A text that is only a phone number or a short code: an optional plus, ASCII digits and the usual separators. */
private val BARE_NUMBER = Regex("""\+?\(?[0-9](?:[0-9 ().\-]*[0-9])?""")

/** Fewer digits than this is a short code ("30007"), which is never regrouped. */
private const val MIN_GROUPED_DIGITS = 7

/** The digits of [text] in ASCII, in order, with everything else dropped: "۰۵۵۵-۳۴" gives "055534". */
fun asciiDigits(text: String): String = buildString {
    text.forEach { c -> Character.digit(c, DECIMAL).takeIf { it >= 0 }?.let { append('0' + it) } }
}

private const val DECIMAL = 10

/** True when [text] is a phone number or short code and nothing else. */
fun isBareNumber(text: String): Boolean = BARE_NUMBER.matches(text.trim())

/**
 * [text] as shown on screen: a bare number grouped by [group] (the platform formatter; null or blank keeps the number
 * as it was) and written with the digits of [locale]; any other text unchanged. Free of Android, for JVM tests.
 */
fun displayNumber(text: String, locale: Locale, group: (String) -> String?): String {
    val trimmed = text.trim()
    if (trimmed.isEmpty() || !isBareNumber(trimmed)) return text
    val grouped = if (trimmed.count { it.isDigit() } >= MIN_GROUPED_DIGITS) {
        group(trimmed)?.takeIf { it.isNotBlank() } ?: trimmed
    } else {
        trimmed
    }
    return localizeDigits(grouped, locale)
}

/** [displayNumber] with the phone's country grouping and the app's language. */
fun displayNumber(context: Context, text: String): String =
    displayNumber(text, context.uiLocale) { PhoneNumberGrouping.format(context, it) }

/** [displayNumber] for composables; follows the app's language. */
@Composable
@ReadOnlyComposable
fun displayNumber(text: String): String {
    val context = LocalContext.current
    return displayNumber(text, uiLocale()) { PhoneNumberGrouping.format(context, it) }
}

/** The platform formatter with the SIM's country (else the phone's region), cached per number. */
private object PhoneNumberGrouping {
    @Volatile
    private var country: String? = null
    private val cache = ConcurrentHashMap<String, String>()

    fun format(context: Context, number: String): String? {
        cache[number]?.let { return it }
        val iso = countryOf(context).ifBlank { return null }
        val formatted = runCatching { PhoneNumberUtils.formatNumber(number, iso) }.getOrNull() ?: return null
        cache[number] = formatted
        return formatted
    }

    private fun countryOf(context: Context): String = country ?: run {
        val sim = runCatching {
            context.applicationContext.getSystemService(TelephonyManager::class.java)?.simCountryIso
        }.getOrNull()
        (sim?.takeIf { it.isNotBlank() } ?: Locale.getDefault().country).uppercase(Locale.ROOT).also { country = it }
    }
}

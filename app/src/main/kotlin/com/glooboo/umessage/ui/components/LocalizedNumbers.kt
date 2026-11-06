package com.glooboo.umessage.ui.components

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import java.text.DecimalFormatSymbols
import java.util.Locale

/*
 * Counts, times and sizes in the digits of the app's language: ۰-۹ in Persian, 0-9 in English. Never use
 * these for phone numbers, verification codes or message text, which keep the digits they came with.
 */

/** [value] written with the digits of [locale], without grouping. */
fun localizedNumber(value: Long, locale: Locale): String = String.format(locale, "%d", value)

fun localizedNumber(value: Int, locale: Locale): String = localizedNumber(value.toLong(), locale)

/** [text] with its ASCII digits replaced by the digits of [locale], for labels such as "99+" or "200KB". */
fun localizeDigits(text: String, locale: Locale): String {
    val zero = DecimalFormatSymbols.getInstance(locale).zeroDigit
    if (zero == '0') return text
    return buildString(text.length) {
        text.forEach { c -> append(if (c in '0'..'9') zero + (c - '0') else c) }
    }
}

/** The language the app's resources are shown in (the per-app language when one is set). */
val Context.uiLocale: Locale get() = resources.configuration.locales[0]

/** [uiLocale] for composables. */
@Composable
@ReadOnlyComposable
fun uiLocale(): Locale = LocalConfiguration.current.locales[0]

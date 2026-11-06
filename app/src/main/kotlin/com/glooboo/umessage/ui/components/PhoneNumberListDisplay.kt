package com.glooboo.umessage.ui.components

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/*
 * N2, CL-10: a group conversation is titled with its people joined by ", " ("5559876, Sara", or "a, b, c +2" for a big
 * group). Each of them goes through [displayNumber] on its own, so a number in a group title is grouped and written in
 * the app's digits like everywhere else, and in an Arabic-script language the list is joined with the Arabic comma
 * "،", which sits on the correct side in right-to-left text. Put the result through [isolateNumbers] so each number
 * keeps its own order. Titles without a bare number (names only, a custom group name) are left as they are.
 */

/** How group titles are joined (see joinGroupNames). */
private const val TITLE_SEPARATOR = ", "

/** The "+2" a big group's title ends with. */
private val MORE_PEOPLE = Regex("""^(.*) \+(\d+)$""")

/** Languages written in Arabic script, which use "،" between list items. */
private val ARABIC_COMMA_LANGUAGES = setOf("fa", "ar", "ur", "ps", "ckb", "sd", "ug")

/** The list separator for [locale]: "، " in Arabic-script languages, else ", ". */
fun listSeparator(locale: Locale): String =
    if (locale.language in ARABIC_COMMA_LANGUAGES) "، " else TITLE_SEPARATOR

/**
 * [title] as shown on screen: a single name or number through [display] (a [displayNumber]); a list joined by ", "
 * with each part through [display] and joined with the separator of [locale]. Free of Android, for JVM tests.
 */
fun displayTitle(title: String, locale: Locale, display: (String) -> String): String {
    val parts = title.split(TITLE_SEPARATOR)
    if (parts.size < 2) return display(title)
    val more = MORE_PEOPLE.find(parts.last())
    val people = if (more != null) parts.dropLast(1) + more.groupValues[1] else parts
    if (people.none { isBareNumber(it) }) return title
    val joined = people.joinToString(listSeparator(locale)) { display(it) }
    return if (more == null) joined else "$joined +${localizeDigits(more.groupValues[2], locale)}"
}

/** [displayTitle] with the phone's country grouping and the app's language. */
fun displayTitle(context: Context, title: String): String =
    displayTitle(title, context.uiLocale) { displayNumber(context, it) }

/** [displayTitle] for composables; follows the app's language. */
@Composable
@ReadOnlyComposable
fun displayTitle(title: String): String {
    val context = LocalContext.current
    return displayTitle(title, uiLocale()) { displayNumber(context, it) }
}

package com.glooboo.umessage.ui.spam

import com.google.i18n.phonenumbers.PhoneNumberUtil
import java.util.Locale

/** One country of the home-country picker (I24): its ISO region, its name in the app language and its dial code. */
data class CountryOption(val region: String, val name: String, val dialCode: Int) {
    val label: String get() = "$name (+$dialCode)"
}

object CountryOptions {

    /** Every country libphonenumber knows, sorted by name in [locale]. */
    fun all(locale: Locale): List<CountryOption> {
        val util = PhoneNumberUtil.getInstance()
        return util.supportedRegions
            .map { CountryOption(it, Locale("", it).getDisplayCountry(locale), util.getCountryCodeForRegion(it)) }
            .filter { it.name.isNotBlank() }
            .sortedBy { it.name.lowercase(locale) }
    }

    /** The countries whose name or dial code holds [query]; all of them for a blank query. */
    fun filter(options: List<CountryOption>, query: String): List<CountryOption> {
        val text = query.trim().removePrefix("+").lowercase()
        if (text.isEmpty()) return options
        return options.filter { it.name.lowercase().contains(text) || it.dialCode.toString().startsWith(text) }
    }

    fun nameOf(region: String, locale: Locale): String =
        Locale("", region.trim().uppercase()).getDisplayCountry(locale).ifBlank { region }
}

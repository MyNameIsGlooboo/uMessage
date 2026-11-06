package com.glooboo.umessage.spam.engine

import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil

/**
 * I24: tells whether a sender's number is from another country than the home country the user chose. Only numbers
 * written with a country code (+ or 00) can be foreign: a national number ("0555...") is the home country's own,
 * short codes and sender names have no country, and a value that is not a possible number is left alone.
 */
object RegionBlock {

    private const val INTERNATIONAL_PREFIX = "00"

    fun isForeign(address: String, homeRegion: String): Boolean {
        val util = PhoneNumberUtil.getInstance()
        val home = util.getCountryCodeForRegion(homeRegion.trim().uppercase())
        val international = internationalDigits(address)
        if (home == 0 || international == null) return false
        return try {
            val number = util.parse("+$international", null)
            util.isPossibleNumberWithReason(number) == PhoneNumberUtil.ValidationResult.IS_POSSIBLE &&
                number.countryCode != home
        } catch (_: NumberParseException) {
            false
        }
    }

    /** The digits after the country-code marker, or null for a sender name or a number without one. */
    private fun internationalDigits(address: String): String? {
        val value = address.trim()
        if (value.isEmpty() || value.any { it.isLetter() }) return null
        val digits = value.mapNotNull { c -> c.digitToIntOrNull()?.let { '0' + it } }.joinToString("")
        return when {
            value.startsWith("+") -> digits
            digits.startsWith(INTERNATIONAL_PREFIX) -> digits.drop(INTERNATIONAL_PREFIX.length)
            else -> null
        }?.ifEmpty { null }
    }
}

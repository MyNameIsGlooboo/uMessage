package com.glooboo.umessage.spam.engine

import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber

class NumberNormalizer(private val regionProvider: () -> String) {

    private val util: PhoneNumberUtil by lazy { PhoneNumberUtil.getInstance() }

    fun isSenderId(raw: String) = raw.any { it.isLetter() }

    /** Same rules, but national numbers are read in [region] (e.g. the country of the SIM that received them). */
    fun withRegion(region: String): NumberNormalizer = NumberNormalizer { region }

    /** E.164 for phone numbers, digits for short codes, upper-case for alphanumeric sender ids. */
    fun normalize(raw: String): String {
        val value = raw.trim()
        if (value.isEmpty()) return ""
        if (isSenderId(value)) return value.uppercase()

        val cleaned = cleanDigits(value)
        if (cleaned.isEmpty()) return value.uppercase()

        return try {
            val region = if (cleaned.startsWith("+")) null else region()
            val number = util.parse(cleaned, region)
            // A national value must be a real number of the region, so short codes such as 4430001234 stay as typed.
            val isNumber = if (region == null) {
                util.isPossibleNumberWithReason(number) == PhoneNumberUtil.ValidationResult.IS_POSSIBLE
            } else {
                util.isValidNumber(number)
            }
            if (isNumber) {
                util.format(number, PhoneNumberUtil.PhoneNumberFormat.E164)
            } else {
                cleaned
            }
        } catch (_: NumberParseException) {
            cleaned
        }
    }

    /**
     * True when [a] and [b] are the same sender. Two numbers whose country is known (written with + or 00, or national
     * numbers that fit the region) must agree in full, so +1 555… never matches +44 912…. A short code matches the
     * same code written with the region's country code (30001234 and +1 3000 1234). Only when no region is known at
     * all is a national number ("0555…") compared with the national part of an international one.
     */
    fun sameAddress(a: String, b: String): Boolean {
        val pa = parse(a) ?: return false
        val pb = parse(b) ?: return false
        if (pa.senderId != null || pb.senderId != null) return pa.senderId == pb.senderId
        if (pa.key == pb.key) return true
        if (pa.countryCode != null && pb.countryCode != null) return false
        // At least one side is digits whose country is unknown.
        if (pa.digits == pb.digits) return true
        val known = if (pa.countryCode != null) pa else if (pb.countryCode != null) pb else return false
        val unknown = if (known === pa) pb else pa
        if (unknown.digits == "${known.countryCode}${known.national}") return true
        return region() == null && unknown.digits.trimStart('0').let { it.length >= MIN_NATIONAL && it == known.national }
    }

    /**
     * One value per sender, for sets and maps: "+<country code><national number>" when the country is known (the
     * E.164 form, also for a short code the region allows), plain digits when it is not, upper case for a sender id,
     * "" for a blank value. Two different numbers with a known country never share a key.
     */
    fun comparableKey(raw: String): String = parse(raw)?.key ?: ""

    /** True when [raw] is a phone number whose country is known, i.e. [comparableKey] is a full E.164 value. */
    fun hasCountryCode(raw: String): Boolean = parse(raw)?.countryCode != null

    /**
     * True when [raw] names its country itself (written with + or 00). A national number ("07712 345678") only gets
     * a country from the reader's region, which can change with the SIM or when travelling.
     */
    fun isInternationalForm(raw: String): Boolean = cleanDigits(raw.trim()).startsWith("+")

    /** Forms an address may be compared in when matching a PREFIX rule. */
    fun prefixCandidates(address: String): List<String> {
        val normalized = normalize(address)
        if (normalized.isEmpty()) return emptyList()
        if (isSenderId(address)) return listOf(normalized)
        val raw = cleanDigits(address)
        val candidates = mutableListOf(normalized, normalized.removePrefix("+"), raw)
        // A number of the reader's own country is also written nationally (0555…, 912…), as prefix rules often are.
        val parsed = parse(address)
        val ownCountry = region()?.let { util.getCountryCodeForRegion(it) }
        if (parsed?.countryCode != null && parsed.countryCode == ownCountry) {
            candidates += "+${parsed.countryCode}${parsed.national}"
            candidates += "${parsed.countryCode}${parsed.national}"
            parsed.nationalFormat?.let { candidates += it }
            candidates += parsed.national
        }
        return candidates.distinct()
    }

    fun cleanPrefix(pattern: String): String {
        val trimmed = pattern.trim()
        if (isSenderId(trimmed)) return trimmed.uppercase()
        return cleanDigits(trimmed)
    }

    private fun region(): String? = runCatching { regionProvider() }.getOrNull()?.trim()?.uppercase()?.ifBlank { null }

    /** ASCII digits (Persian and Arabic digits converted) and a leading +; a leading 00 becomes +. */
    private fun cleanDigits(value: String): String {
        val plus = PlusPrefix.startsWithPlus(value)
        val digits = buildString {
            for (c in value) {
                if (c.isDigit()) c.digitToIntOrNull()?.let { append('0' + it) }
            }
        }
        if (digits.isEmpty()) return ""
        return when {
            plus -> "+$digits"
            digits.startsWith("00") -> "+" + digits.drop(2)
            else -> digits
        }
    }

    private class Parsed(
        val senderId: String?,
        /** Null when the number's country is unknown. */
        val countryCode: Int?,
        /** National significant number when the country is known, else all the digits. */
        val national: String,
        val digits: String,
        /** Digits of the national format (e.g. 05550001234), when known. */
        val nationalFormat: String?,
    ) {
        val key: String get() = senderId ?: if (countryCode != null) "+$countryCode$national" else digits
    }

    private fun parse(raw: String): Parsed? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        if (isSenderId(value)) return Parsed(value.uppercase(), null, "", "", null)
        val cleaned = cleanDigits(value)
        if (cleaned.isEmpty()) return Parsed(value.uppercase(), null, "", "", null)
        val digits = cleaned.removePrefix("+")
        val international = cleaned.startsWith("+")
        val region = if (international) null else region() ?: return Parsed(null, null, digits, digits, null)
        val number: PhoneNumber = try {
            util.parse(cleaned, region)
        } catch (_: NumberParseException) {
            // Keep the + so an unknown country code never equals a short code with the same digits.
            return Parsed(null, null, digits, cleaned, null)
        }
        if (!international) {
            val reason = util.isPossibleNumberWithReason(number)
            if (reason != PhoneNumberUtil.ValidationResult.IS_POSSIBLE &&
                reason != PhoneNumberUtil.ValidationResult.IS_POSSIBLE_LOCAL_ONLY
            ) {
                return Parsed(null, null, digits, digits, null)
            }
        }
        val national = util.getNationalSignificantNumber(number)
        val nationalFormat = runCatching {
            util.format(number, PhoneNumberUtil.PhoneNumberFormat.NATIONAL).filter { it.isDigit() }
        }.getOrNull()?.ifEmpty { null }
        return Parsed(null, number.countryCode, national, digits, nationalFormat)
    }

    private companion object {
        /** Shortest national part compared without a country, so short codes are not matched loosely. */
        const val MIN_NATIONAL = 7
    }
}

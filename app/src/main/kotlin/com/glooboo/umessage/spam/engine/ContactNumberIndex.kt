package com.glooboo.umessage.spam.engine

/**
 * Phone numbers (e.g. every number in the address book) mapped to a value, looked up so that two different numbers
 * never merge: when both the stored and the looked-up number have a known country they must be the same E.164
 * number, so +44 555 000 1234 does not find the contact saved as +1 555 000 1234. Only when one side's country is
 * unknown (a contact saved without a country code that does not fit the region, a short code) are the last 9 digits
 * compared, as Fossify did for every number.
 */
class ContactNumberIndex<T>(private val normalizer: NumberNormalizer) {

    private class Entry<T>(val key: String, val hasCountry: Boolean, var value: T)

    private val byKey = HashMap<String, Entry<T>>()
    private val byLastDigits = HashMap<String, MutableList<Entry<T>>>()

    val isEmpty: Boolean get() = byLastDigits.isEmpty()

    /** Adds [value] for [number]. When the number is already there, [replace] decides whether the new value wins. */
    fun add(number: String, value: T, replace: (existing: T) -> Boolean = { false }) {
        val key = normalizer.comparableKey(number)
        if (key.isEmpty()) return
        val existing = byKey[key]
        if (existing != null) {
            if (replace(existing.value)) existing.value = value
            return
        }
        val entry = Entry(key, normalizer.hasCountryCode(number), value)
        byKey[key] = entry
        byLastDigits.getOrPut(lastDigits(key)) { mutableListOf() }.add(entry)
    }

    /** The value stored for [number], or null when no stored number is the same number. */
    fun find(number: String): T? {
        val key = normalizer.comparableKey(number)
        if (key.isEmpty()) return null
        byKey[key]?.let { return it.value }
        val candidates = byLastDigits[lastDigits(key)] ?: return null
        val match = if (normalizer.hasCountryCode(number)) {
            candidates.firstOrNull { !it.hasCountry }
        } else {
            candidates.firstOrNull()
        }
        return match?.value
    }

    fun contains(number: String): Boolean = find(number) != null

    private fun lastDigits(key: String): String {
        if (key.any { it.isLetter() }) return key
        val digits = key.filter { it in '0'..'9' }
        return digits.takeLast(COMPARED_DIGITS)
    }

    private companion object {
        const val COMPARED_DIGITS = 9
    }
}

package com.glooboo.umessage.ui.spam

import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamRule
import com.glooboo.umessage.spam.engine.NumberNormalizer
import org.fossify.commons.models.SimpleContact

/** One number picked from a contact: the contact's [name] and the [number] the rule will match, normalised. */
data class PickedNumber(val name: String, val number: String)

/** What adding picked numbers as rules does: the [rules] to store and how many numbers were [skipped]. */
data class ContactRuleBatch(val rules: List<SpamRule>, val skipped: Int)

/** Pure helpers behind "Choose from contacts": number extraction, search, and de-duplication against existing rules. */
object ContactNumberPicks {
    /** Every distinct number of [contact], normalised with [normalizer]; blank numbers are left out. */
    fun numbersOf(contact: SimpleContact, normalizer: NumberNormalizer): List<PickedNumber> =
        contact.phoneNumbers
            .mapNotNull { phone ->
                val raw = phone.value.trim()
                if (raw.isEmpty()) null else PickedNumber(contact.name, normalizer.normalize(raw))
            }
            .filter { it.number.isNotEmpty() }
            .distinctBy { it.number }

    /** Contacts whose name or any number matches [query] (digits are compared without spaces and dashes). */
    fun matching(contacts: List<SimpleContact>, query: String): List<SimpleContact> {
        val text = query.trim()
        if (text.isEmpty()) return contacts
        val digits = text.filter { it.isDigit() }
        return contacts.filter { contact ->
            contact.name.contains(text, ignoreCase = true) ||
                (digits.length >= MIN_DIGITS && contact.phoneNumbers.any { digitsOf(it.value).contains(digits) })
        }
    }

    /** [picks] without repeats: the same number, also written in its national and international form, once. */
    fun distinct(picks: List<PickedNumber>): List<PickedNumber> {
        val kept = ArrayList<PickedNumber>()
        for (pick in picks) {
            if (kept.none { sameNumber(it.number, pick.number) }) kept += pick
        }
        return kept
    }

    /**
     * One rule per picked number. A number that is not a valid rule number, or that already has a number rule (in
     * either list, so an allowed number is never silently turned into a blocked one), is skipped and counted.
     * The contact's name becomes the rule's note so the row says who it is.
     */
    fun plan(
        kind: ListKind,
        subscriptionId: Int?,
        existing: List<SpamRule>,
        picks: List<PickedNumber>,
    ): ContactRuleBatch {
        val known = existing.filter { it.type == RuleType.NUMBER }.map { it.pattern }.toMutableList()
        val rules = ArrayList<SpamRule>()
        var skipped = 0
        for (pick in picks) {
            val valid = RuleValidator.validate(kind, RuleType.NUMBER, pick.number) as? RuleValidation.Valid
            if (valid == null || known.any { sameNumber(it, valid.pattern) }) {
                skipped++
                continue
            }
            known += valid.pattern
            rules += SpamRule(
                kind = kind,
                type = RuleType.NUMBER,
                pattern = valid.pattern,
                subscriptionId = subscriptionId,
                note = pick.name.take(MAX_NOTE),
            )
        }
        return ContactRuleBatch(rules, skipped)
    }

    private fun digitsOf(value: String): String = value.filter { it.isDigit() }

    private fun sameNumber(a: String, b: String): Boolean =
        digitsOf(a) == digitsOf(b) || RuleValidator.sameNumber(a, b)

    private const val MIN_DIGITS = 2
    private const val MAX_NOTE = 80
}

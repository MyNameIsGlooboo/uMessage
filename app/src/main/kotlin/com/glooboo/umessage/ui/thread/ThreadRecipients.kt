package com.glooboo.umessage.ui.thread

import com.glooboo.umessage.messaging.isShortCodeWithLetters
import com.glooboo.umessage.spam.engine.NumberNormalizer
import com.glooboo.umessage.ui.components.isBareNumber
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.fossify.commons.models.PhoneNumber
import org.fossify.commons.models.SimpleContact

/**
 * Who a conversation is with: always a list, one person or a group. Sending, scheduling, the SIM choice and the
 * menu all read the people from here, so a group conversation (MC-13) needs no special case: every send goes to
 * [addresses], whatever their number.
 *
 * Immutable: [plus] and [minus] return a new value. The contacts inside are the same objects the messages hold.
 */
class ThreadRecipients(val contacts: List<SimpleContact> = emptyList()) {

    val size: Int get() = contacts.size
    fun isEmpty(): Boolean = contacts.isEmpty()
    fun isNotEmpty(): Boolean = contacts.isNotEmpty()

    /** More than one person: rows show sender names and a send can go as one group MMS. */
    val isGroup: Boolean get() = contacts.size > 1 && distinctAddresses.size > 1

    /** The only person, or null for none or a group. */
    val single: SimpleContact? get() = contacts.singleOrNull()

    /**
     * Every number to send to, as stored (normalized), in order. The same phone written two ways ("0912..." and
     * "+98912...") counts once, so a send never goes to one person twice (B15).
     */
    val addresses: List<String> get() = dedupe(contacts.flatMap { it.phoneNumbers }.map { it.normalizedNumber })

    /** [addresses] without repeats: how many different people a send reaches. */
    val distinctAddresses: List<String> get() = addresses.distinct()

    /** A service short code ("300012"): texts may go back to it, but it takes no calls (TH-30). */
    val hasShortCode: Boolean get() = addresses.any { isNumericShortCode(it) }

    /** A sender name ("Bank") instead of a number: nobody can reply to it. */
    val hasShortCodeWithLetters: Boolean get() = addresses.any { isShortCodeWithLetters(it) }

    /** The single person's address as the spam rules store it: digits for numbers, the id for sender names. */
    val reportableAddress: String?
        get() {
            val number = single?.phoneNumbers?.firstOrNull() ?: return null
            return number.normalizedNumber.ifBlank { number.value }.trim().ifEmpty { null }
        }

    /** The single person's first number as shown (not normalized), for "Copy number" and "Add to contacts". */
    val singleNumber: String? get() = single?.phoneNumbers?.firstOrNull()?.value

    fun contains(rawId: Int): Boolean = contacts.any { it.rawId == rawId }

    /** Adds [contact] unless someone with the same raw id, or the same phone number, is already in (B15). */
    operator fun plus(contact: SimpleContact): ThreadRecipients =
        if (contains(contact.rawId) || hasSamePhoneAs(contact)) this else ThreadRecipients(contacts + contact)

    private fun hasSamePhoneAs(contact: SimpleContact): Boolean {
        val mine = contacts.flatMap { it.phoneNumbers }.map { it.normalizedNumber }
        return contact.phoneNumbers.isNotEmpty() &&
            contact.phoneNumbers.all { added -> mine.any { sameAddress(it, added.normalizedNumber) } }
    }

    operator fun minus(rawId: Int): ThreadRecipients = ThreadRecipients(contacts.filter { it.rawId != rawId })

    /** Same people (by address) as [other], in the same order. */
    fun sameAddressesAs(other: ThreadRecipients): Boolean = addresses == other.addresses

    /** A mutable copy for the message model, which keeps participants as an ArrayList. */
    fun toArrayList(): ArrayList<SimpleContact> = ArrayList(contacts)

    companion object {
        // No region: a national number ("0912...") matches the national part of an international one ("+98912...").
        private val matcher = NumberNormalizer { "" }

        private fun sameAddress(a: String, b: String): Boolean = a == b || matcher.sameAddress(a, b)

        /** [numbers] in order, each phone once. */
        internal fun dedupe(numbers: List<String>): List<String> {
            val kept = ArrayList<String>()
            for (number in numbers) if (kept.none { sameAddress(it, number) }) kept.add(number)
            return kept
        }

        /**
         * Recipients for numbers that have no contact yet (a new conversation, or a link): one entry per number.
         * A lone number is shown as [name] when one was given.
         */
        fun fromNumbers(numbers: List<String>, name: String = ""): ThreadRecipients =
            ThreadRecipients(numbers.map { number -> contactForNumber(number, if (numbers.size == 1) name.ifBlank { number } else number) })

        /**
         * The numbers in a THREAD_NUMBER intent extra: one number, or a JSON list of them for a group (as
         * "Done" in the people panel and a new group conversation send it).
         */
        fun numbersFromExtra(extra: String?): List<String> {
            if (extra == null) return emptyList()
            if (extra.startsWith('[') && extra.endsWith(']')) {
                val type = object : TypeToken<List<String>>() {}.type
                return Gson().fromJson<List<String>>(extra, type).orEmpty()
            }
            return listOf(extra)
        }

        /**
         * The phone's participants for a new thread, with each number written the way the intent gave it (with
         * its "+"), and that number as the name where the name was only the bare number. Changes [contacts].
         */
        fun withIntentNumbers(contacts: ArrayList<SimpleContact>, intentNumbers: List<String>): ArrayList<SimpleContact> {
            for (number in intentNumbers) {
                val numberWithoutPlus = number.replace("+", "")
                for (participant in contacts) {
                    participant.phoneNumbers = participant.phoneNumbers.mapTo(ArrayList()) {
                        if (numberWithoutPlus == it.normalizedNumber.trim()) {
                            if (participant.name == it.normalizedNumber) participant.name = number
                            PhoneNumber(number, 0, "", number)
                        } else {
                            PhoneNumber(it.normalizedNumber, 0, "", it.normalizedNumber)
                        }
                    }
                }
            }
            return contacts
        }

        /**
         * What was typed in the people editor as a phone number in ASCII digits (the app's own digits are accepted),
         * or null when it is not a number: "abc" must not become a recipient.
         */
        fun typedNumberOrNull(typed: String): String? {
            val ascii = typed.trim().map { c -> Character.digit(c, DECIMAL).takeIf { it >= 0 }?.let { '0' + it } ?: c }
                .joinToString("")
            return ascii.takeIf { isBareNumber(it) }
        }

        private const val DECIMAL = 10

        /** A bare contact for [number], keyed by the number's hash as the old code did. */
        fun contactForNumber(number: String, name: String = number): SimpleContact = SimpleContact(
            rawId = number.hashCode(),
            contactId = number.hashCode(),
            name = name,
            photoUri = "",
            phoneNumbers = arrayListOf(PhoneNumber(number, 0, "", number)),
            birthdays = ArrayList(),
            anniversaries = ArrayList(),
        )
    }
}

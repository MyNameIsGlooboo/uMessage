package com.glooboo.umessage.ui.newconversation

import android.content.ClipboardManager
import org.fossify.commons.extensions.normalizeString
import org.fossify.commons.models.SimpleContact

/*
 * The new-conversation picker's list logic, free of Compose: matching, list keys, letter sections and the clipboard
 * check behind the "Paste number" chip.
 */

/** True when [clipboard] holds text; false when it is empty or Android does not let the app look (no focus). */
internal fun clipboardHasText(clipboard: ClipboardManager?): Boolean =
    runCatching { clipboard?.primaryClipDescription?.hasMimeType("text/*") == true }.getOrDefault(false)

/**
 * Contacts whose name or any number matches [query], best matches (name prefix) first. A number is matched however
 * it is typed (MC-09): with spaces or dashes, in Persian digits, as 0555... or +1555....
 */
internal fun filterContacts(all: List<SimpleContact>, query: String): List<SimpleContact> {
    if (query.isBlank()) return all
    val normalized = query.normalizeString()
    val numberForms = numberSearchForms(query)
    fun formsMatch(value: String) = numberForms != null && numberMatches(value, numberForms)
    return all
        .filter { contact ->
            contact.phoneNumbers.any {
                it.normalizedNumber.contains(query, true) || formsMatch(it.normalizedNumber) || formsMatch(it.value)
            } ||
                contact.name.contains(query, true) ||
                contact.name.contains(normalized, true) ||
                contact.name.normalizeString().contains(query, true)
        }
        .sortedBy { !it.name.startsWith(query, true) }
}

/** A contact row with a list key that is unique even when two contacts share ids. */
internal data class ContactItem(val key: String, val contact: SimpleContact)

/**
 * MC-12: phone contacts and Fossify private contacts can share a raw and contact id, and a lazy list crashes on a
 * repeated key. The first number is part of the key, and any key still repeated gets a counter.
 */
internal fun contactItems(contacts: List<SimpleContact>): List<ContactItem> {
    val seen = HashMap<String, Int>()
    return contacts.map { contact ->
        val firstNumber = contact.phoneNumbers.firstOrNull()?.normalizedNumber.orEmpty()
        val base = "c-${contact.rawId}-${contact.contactId}-$firstNumber"
        val count = (seen[base] ?: 0) + 1
        seen[base] = count
        ContactItem(if (count == 1) base else "$base#$count", contact)
    }
}

internal fun letterGroups(contacts: List<ContactItem>): List<Pair<String, List<ContactItem>>> =
    contacts.groupBy { (_, contact) -> sectionLetter(contact.name) }.toList().sortedBy { it.first == "#" }

/** CL-36: the index letter of a name; digits, symbols and nameless contacts share "#" (shown last). */
internal fun sectionLetter(name: String): String {
    val first = name.firstOrNull { it.isLetterOrDigit() } ?: return "#"
    if (first.isDigit()) return "#"
    return first.toString().normalizeString().uppercase().take(1).ifEmpty { "#" }
}

/**
 * CL-36: the numbers shown under a contact's name, leaving out one that only repeats the name (an unnamed contact
 * is titled with its own number).
 */
internal fun supportingNumbers(name: String, numbers: List<String>): List<String> {
    val nameDigits = name.filter { it.isDigit() }
    val nameIsNumber = name.none { it.isLetter() } && nameDigits.isNotEmpty()
    if (!nameIsNumber) return numbers
    val nameTail = nameDigits.takeLast(SAME_NUMBER_TAIL)
    return numbers.filter { number -> number.filter { it.isDigit() }.takeLast(SAME_NUMBER_TAIL) != nameTail }
}

/** Numbers are compared by their last digits, so "0555..." and "+1555..." count as the same. */
private const val SAME_NUMBER_TAIL = 9

/** "Send to 0555..." as the first row once a number is typed. */

package com.glooboo.umessage.ui.newconversation

import java.text.Collator
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class ContactsLoaderTest {
    private val collator = Collator.getInstance(Locale.ENGLISH)

    private fun row(contactId: Int, name: String, number: String, normalized: String = number, rawId: Int = contactId) =
        PhoneRow(contactId, rawId, name, "", number, normalized, type = 2, label = "", isPrimary = false)

    @Test fun displayNameOnlyContactIsKept() {
        val contacts = groupPhoneRows(listOf(row(1, "Maya", "+15550001")), collator)
        assertEquals(listOf("Maya"), contacts.map { it.name })
    }

    @Test fun namelessContactFallsBackToNumber() {
        val contacts = groupPhoneRows(listOf(row(1, "", "+15550001")), collator)
        assertEquals("+15550001", contacts.single().name)
    }

    @Test fun numbersOfOneContactAreMergedAndDeduplicated() {
        val contacts = groupPhoneRows(
            listOf(
                row(1, "Maya", "+1 555 0001", normalized = "+15550001"),
                row(1, "Maya", "+15550001", normalized = "+15550001", rawId = 7),
                row(1, "Maya", "+15550002"),
            ),
            collator,
        )
        assertEquals(1, contacts.size)
        assertEquals(listOf("+15550001", "+15550002"), contacts.single().phoneNumbers.map { it.normalizedNumber })
    }

    @Test fun sortedByName() {
        val contacts = groupPhoneRows(listOf(row(1, "zoe", "1"), row(2, "Adam", "2"), row(3, "maya", "3")), collator)
        assertEquals(listOf("Adam", "maya", "zoe"), contacts.map { it.name })
    }
}

package com.glooboo.umessage.ui.newconversation

import com.google.common.truth.Truth.assertThat
import org.fossify.commons.models.PhoneNumber
import org.fossify.commons.models.SimpleContact
import org.junit.Test

class FilterContactsTest {

    private fun contact(id: Int, name: String, vararg numbers: String) = SimpleContact(
        rawId = id,
        contactId = id,
        name = name,
        photoUri = "",
        phoneNumbers = numbers.mapTo(ArrayList()) { PhoneNumber(it, 0, "", it) },
        birthdays = ArrayList(),
        anniversaries = ArrayList(),
    )

    private val anna = contact(1, "Anna Berg", "5550101")
    private val joanna = contact(2, "Joanna", "5550102")
    private val zoe = contact(3, "Zoë", "5550199", "5550200")
    private val all = listOf(joanna, anna, zoe)

    @Test
    fun blankQueryKeepsEveryone() {
        assertThat(filterContacts(all, " ")).containsExactlyElementsIn(all).inOrder()
    }

    @Test
    fun nameMatchIgnoresCaseAndPutsPrefixMatchesFirst() {
        assertThat(filterContacts(all, "ANNA")).containsExactly(anna, joanna).inOrder()
    }

    @Test
    fun anyNumberOfAContactMatches() {
        assertThat(filterContacts(all, "0200")).containsExactly(zoe)
    }

    @Test
    fun accentsAreIgnored() {
        assertThat(filterContacts(all, "zoe")).containsExactly(zoe)
    }

    @Test
    fun noMatchIsEmpty() {
        assertThat(filterContacts(all, "xyz")).isEmpty()
    }

    // MC-09: numbers typed the way people write them.
    private val sara = contact(4, "Sara", "+447123456789")
    private val ali = contact(5, "Ali", "07351112233")
    private val people = listOf(sara, ali, anna)

    @Test
    fun nationalNumberWithSpacesFindsTheInternationalContact() {
        assertThat(filterContacts(people, "0712 345")).containsExactly(sara)
    }

    @Test
    fun persianDigitsFindTheContact() {
        assertThat(filterContacts(people, "۰۷۱۲۳۴۵")).containsExactly(sara)
    }

    @Test
    fun internationalNumberFindsTheNationalContact() {
        assertThat(filterContacts(people, "+44 735 111")).containsExactly(ali)
        assertThat(filterContacts(people, "0044735111")).containsExactly(ali)
    }

    @Test
    fun dashesAreIgnored() {
        assertThat(filterContacts(people, "555-0101")).containsExactly(anna)
    }

    // MC-08
    @Test
    fun typedNumberBecomesPlainDigits() {
        assertThat(typedPhoneNumber("۰۷۱۲ ۳۴۵-۶۷۸۹")).isEqualTo("07123456789")
        assertThat(typedPhoneNumber("+۴۴ ۷۱۲ ۳۴۵ ۶۷۸۹")).isEqualTo("+447123456789")
        assertThat(typedPhoneNumber("(021) 8888-1234")).isEqualTo("02188881234")
        assertThat(typedPhoneNumber("Sara")).isNull()
        assertThat(typedPhoneNumber("AB-CD0")).isNull()
        assertThat(typedPhoneNumber("someone@example.com")).isNull()
    }

    // MC-12
    @Test
    fun contactItemKeysAreUniqueEvenWhenIdsRepeat() {
        val phone = contact(12, "Sara", "5550101")
        val private = contact(12, "Sara Work", "5550199")
        val twin = contact(12, "Sara again", "5550101")
        val keys = contactItems(listOf(phone, private, twin)).map { it.key }
        assertThat(keys).containsNoDuplicates()
    }
}

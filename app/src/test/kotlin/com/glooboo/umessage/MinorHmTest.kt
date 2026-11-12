package com.glooboo.umessage

import com.glooboo.umessage.extensions.joinMmsText
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.ui.newconversation.filterContacts
import com.google.common.truth.Truth.assertThat
import org.fossify.commons.models.PhoneNumber
import org.fossify.commons.models.SimpleContact
import org.junit.Test

class MinorHmTest {

    private fun contact(id: Int, name: String, number: String) = SimpleContact(
        rawId = id,
        contactId = id,
        name = name,
        photoUri = "",
        phoneNumbers = arrayListOf(PhoneNumber(number, 0, "", number)),
        birthdays = ArrayList(),
        anniversaries = ArrayList(),
    )

    private fun conversation(archived: Boolean) = Conversation(
        threadId = 1, snippet = "s", date = 1, read = true, title = "t", photoUri = "",
        isGroupConversation = false, phoneNumber = "1", isArchived = archived,
    )

    @Test
    fun numberPrefixHitsComeBeforeInnerNumberHits() {
        val inner = contact(1, "Alpha", "9915550")
        val prefix = contact(2, "Beta", "5550199")
        assertThat(filterContacts(listOf(inner, prefix), "555")).containsExactly(prefix, inner).inOrder()
    }

    @Test
    fun mmsTextSlidesAreJoinedInOrder() {
        assertThat(joinMmsText("", "one")).isEqualTo("one")
        assertThat(joinMmsText("one", "two")).isEqualTo("one\ntwo")
        assertThat(joinMmsText("one", "")).isEqualTo("one")
    }

    @Test
    fun archivedFlagCountsOnlyWhenAsked() {
        val a = conversation(archived = true)
        val b = conversation(archived = false)
        assertThat(Conversation.areContentsTheSame(a, b)).isTrue()
        assertThat(Conversation.areContentsTheSame(a, b, compareArchived = true)).isFalse()
    }
}

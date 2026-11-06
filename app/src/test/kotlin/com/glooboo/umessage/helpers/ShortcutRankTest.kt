package com.glooboo.umessage.helpers

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.models.Conversation
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** MC-28: chats with international numbers were ranked last (99) and left out of Share suggestions. */
@RunWith(AndroidJUnit4::class)
class ShortcutRankTest {
    private val helper = ShortcutHelper(ApplicationProvider.getApplicationContext())

    private fun conversation(number: String, archived: Boolean = false) = Conversation(
        threadId = 7,
        snippet = "",
        date = 0,
        read = true,
        title = "Ali",
        photoUri = "",
        isGroupConversation = false,
        phoneNumber = number,
        isScheduled = false,
        usesCustomTitle = false,
        isArchived = archived,
    )

    @Test
    fun internationalAndFormattedNumbersArePresented() {
        assertThat(helper.shouldPresentShortcut(conversation("+447123456789"))).isTrue()
        assertThat(helper.shouldPresentShortcut(conversation("0712 345 6789"))).isTrue()
        assertThat(helper.shouldPresentShortcut(conversation("+1 (555) 010-0199"))).isTrue()
        assertThat(helper.shouldPresentShortcut(conversation("07123456789"))).isTrue()
    }

    @Test
    fun senderNamesEmptyNumbersAndArchivedChatsAreNot() {
        assertThat(helper.shouldPresentShortcut(conversation("BANK"))).isFalse()
        assertThat(helper.shouldPresentShortcut(conversation(""))).isFalse()
        assertThat(helper.shouldPresentShortcut(conversation("+"))).isFalse()
        assertThat(helper.shouldPresentShortcut(conversation("+447123456789", archived = true))).isFalse()
    }
}

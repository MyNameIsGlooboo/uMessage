package com.glooboo.umessage.ui.thread

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThreadMenuShareShortcutTest {

    private fun menu(
        pin: Boolean = true,
        hasItems: Boolean = true,
        recycleBin: Boolean = false,
        conversation: Boolean = true,
    ) =
        buildThreadMenu(
            ThreadMenuInput(
                recipients = ThreadRecipients.fromNumbers(listOf("111")), hasItems = hasItems,
                hasConversation = conversation, isArchived = false, isRecycleBin = recycleBin, archiveAvailable = true,
                singleNumberIsSaved = false, reportedSender = null, blockedSender = null, pinShortcutSupported = pin,
            )
        )

    @Test
    fun addToHomeScreenNeedsLauncherSupport() {
        assertThat(menu(pin = true).addToHomeScreen).isTrue()
        assertThat(menu(pin = false).addToHomeScreen).isFalse()
    }

    @Test
    fun addToHomeScreenNeedsTheConversationAndNotTheRecycleBin() {
        assertThat(menu(conversation = false).addToHomeScreen).isFalse()
        assertThat(menu(recycleBin = true).addToHomeScreen).isFalse()
    }

    @Test
    fun shareAsTextNeedsMessagesAndNotTheRecycleBin() {
        assertThat(menu().shareAsText).isTrue()
        assertThat(menu(hasItems = false).shareAsText).isFalse()
        assertThat(menu(recycleBin = true).shareAsText).isFalse()
    }
}

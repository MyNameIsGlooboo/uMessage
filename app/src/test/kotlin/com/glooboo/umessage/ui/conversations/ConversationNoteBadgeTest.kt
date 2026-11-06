package com.glooboo.umessage.ui.conversations

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A chat with a private note on one of its messages is marked in the list, and the mark is spoken. */
@RunWith(AndroidJUnit4::class)
class ConversationNoteBadgeTest {

    @get:Rule
    val compose = createComposeRule()

    private fun row(hasNotes: Boolean) = ConversationRow(
        threadId = 1, title = "BankX", snippet = "Paid 12.50", dateLabel = "9:41",
        unread = false, unreadBadge = "", unreadCount = 0, pinned = false, muted = false, hasDraft = false,
        scheduled = false, simSlot = null, photoUri = "", isGroup = false, phoneNumber = "BankX",
        hasNotes = hasNotes,
    )

    private fun show(row: ConversationRow) {
        compose.setContent {
            ConversationsTheme {
                ConversationItem(
                    row = row,
                    selected = false,
                    selecting = false,
                    callbacks = ConversationsCallbacks.None,
                    canArchive = true,
                )
            }
        }
    }

    @Test
    fun aRowWithNotesSaysSo() {
        show(row(hasNotes = true))

        compose.onNode(hasContentDescription("Has notes", substring = true)).assertIsDisplayed()
    }

    @Test
    fun aRowWithoutNotesDoesNotSayIt() {
        show(row(hasNotes = false))

        compose.onNode(hasContentDescription("BankX", substring = true)).assertIsDisplayed()
        compose.onNode(hasContentDescription("Has notes", substring = true)).assertDoesNotExist()
    }

    @Test
    fun theRowStaysOneLineOfPreviewWithTheMark() {
        show(row(hasNotes = true))

        compose.onNode(hasContentDescription("Paid 12.50", substring = true)).assertIsDisplayed()
    }
}

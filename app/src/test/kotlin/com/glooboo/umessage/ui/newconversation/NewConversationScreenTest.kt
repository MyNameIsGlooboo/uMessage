package com.glooboo.umessage.ui.newconversation

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.ui.theme.UMessageTheme
import org.fossify.commons.models.PhoneNumber
import org.fossify.commons.models.SimpleContact
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** MC-12: a phone contact and a private contact with the same ids used to crash the list on a repeated key. */
@RunWith(AndroidJUnit4::class)
class NewConversationScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private fun contact(name: String, number: String) = SimpleContact(
        rawId = 12,
        contactId = 12,
        name = name,
        photoUri = "",
        phoneNumbers = arrayListOf(PhoneNumber(number, 0, "", number)),
        birthdays = ArrayList(),
        anniversaries = ArrayList(),
    )

    @Test
    fun contactsSharingIdsBothShow() {
        compose.setContent {
            UMessageTheme(darkTheme = false, dynamicColor = false, syncWindow = false) {
                NewConversationScreen(
                    contacts = listOf(contact("Sara", "5550101"), contact("Sara Work", "5550199")),
                    suggestions = emptyList(),
                    loaded = true,
                    hasContactsPermission = true,
                    onBack = {},
                    onRequestPermission = {},
                    onSubmitAddress = {},
                    onPickNumber = { _, _ -> },
                )
            }
        }
        compose.onNodeWithText("Sara").assertExists()
        compose.onNodeWithText("Sara Work").assertExists()
    }

    private fun showScreen() {
        compose.setContent {
            UMessageTheme(darkTheme = false, dynamicColor = false, syncWindow = false) {
                NewConversationScreen(
                    contacts = listOf(contact("Sara", "5550101")),
                    suggestions = emptyList(),
                    loaded = true,
                    hasContactsPermission = true,
                    onBack = {},
                    onRequestPermission = {},
                    onSubmitAddress = {},
                    onPickNumber = { _, _ -> },
                )
            }
        }
    }

    private fun copy(text: String) {
        ApplicationProvider.getApplicationContext<Context>().getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("number", text))
    }

    /** B11: a number copied before the picker opens (Copy number in a thread) offers the chip, in view. */
    @Test
    fun aCopiedNumberOffersPasteAtOnce() {
        copy("5551888")
        showScreen()
        compose.onNodeWithText("Paste number").assertIsDisplayed()
    }

    /** B11: a number copied while the picker is open (from another app in split screen) shows the chip too. */
    @Test
    fun aNumberCopiedLaterOffersPaste() {
        showScreen()
        compose.onNodeWithText("Paste number").assertDoesNotExist()
        copy("5551888")
        compose.waitForIdle()
        compose.onNodeWithText("Paste number").assertIsDisplayed()
        compose.onNodeWithText("Paste number").performClick()
        compose.onNodeWithText("Send to", substring = true).assertExists()
    }
}

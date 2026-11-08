package com.glooboo.umessage.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.ui.theme.UMessageTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** R10: the Backup page says how to use Google Drive and leads to the "Move from another app" guide. */
@RunWith(AndroidJUnit4::class)
class MoveFromAppTest {
    @get:Rule
    val compose = createComposeRule()

    private var imports = 0

    private fun show() = compose.setContent {
        UMessageTheme(darkTheme = false, dynamicColor = false, syncWindow = false) {
            SettingsScreen(SettingsUiState(), SettingsActions(onImport = { imports++ }), SettingsPage.Backup)
        }
    }

    @Test
    fun `the Backup page carries the Google Drive hint behind its info icon`() {
        show()
        compose.onNodeWithContentDescription("More information").performScrollTo().performClick()
        compose.onNodeWithText("To use Google Drive, pick it in the file picker.").assertIsDisplayed()
    }

    @Test
    fun `the guide lists the steps and its button opens the import picker`() {
        show()
        compose.onNodeWithText("Move from another app").performScrollTo().performClick()
        assertEquals(3, moveFromAppSteps.size)
        compose.onNodeWithText("Switching to uMessage? Your old messages can come with you.").assertIsDisplayed()
        compose.onNodeWithText("To use Google Drive, pick it in the file picker.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Import messages").performScrollTo().performClick()
        assertEquals(1, imports)
    }
}

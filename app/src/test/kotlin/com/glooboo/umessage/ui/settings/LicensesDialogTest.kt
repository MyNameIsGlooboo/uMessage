package com.glooboo.umessage.ui.settings

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.ui.theme.UMessageTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Settings > About > Open-source licences links to the full licence texts: the app's LICENSE, the OFL and Apache 2.0.
 */
@RunWith(AndroidJUnit4::class)
class LicensesDialogTest {
    @get:Rule
    val compose = createComposeRule()

    private val opened = mutableListOf<String>()

    private fun openLicenses(expand: String) {
        compose.setContent {
            UMessageTheme(darkTheme = false, dynamicColor = false, syncWindow = false) {
                SettingsScreen(
                    SettingsUiState(),
                    SettingsActions(onOpenLink = { opened += it }),
                    SettingsPage.Licenses,
                )
            }
        }
        compose.onNodeWithText(expand).performScrollTo().performClick()
    }

    @Test
    fun `the app licence links to LICENSE in the repository`() {
        openLicenses("uMessage and Fossify Messages")
        compose.onNodeWithText("Read the full GPL-3.0 text").performScrollTo().performClick()
        assertEquals(listOf("https://github.com/MyNameIsGlooboo/uMessage/blob/main/LICENSE"), opened)
    }

    @Test
    fun `the bundled fonts link to the Open Font License`() {
        openLicenses("Fonts")
        compose.onNodeWithText("Read the full OFL-1.1 text").performScrollTo().performClick()
        assertEquals(listOf("https://openfontlicense.org/open-font-license-official-text/"), opened)
    }
}

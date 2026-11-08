package com.glooboo.umessage.ui.onboarding

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.ui.settings.AppLanguage
import com.glooboo.umessage.ui.theme.UMessageTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Welcome page language chip (and no Restore), and the "keep blocked messages" row. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class WelcomeChoicesTest {
    @get:Rule
    val compose = createComposeRule()

    private fun welcome(onLanguage: ((AppLanguage) -> Unit)?) = compose.setContent {
        UMessageTheme(darkTheme = false, dynamicColor = false, syncWindow = false) {
            WelcomePage(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                reduceMotion = true,
                onLanguage = onLanguage,
            )
        }
    }

    @Test
    fun `the language chip shows the phone language by its own name and the list starts on Phone language`() {
        welcome(onLanguage = {})
        compose.onNodeWithContentDescription("Language, English").assertIsDisplayed().performClick()
        compose.onNodeWithText("Phone language").assertIsDisplayed()
        compose.onNodeWithText("فارسی").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `picking a language reports it`() {
        var picked: AppLanguage? = null
        welcome(onLanguage = { picked = it })
        compose.onNodeWithContentDescription("Language, English").performClick()
        compose.onNodeWithText("فارسی").performScrollTo().performClick()
        assertEquals(AppLanguage.PERSIAN, picked)
    }

    @Test
    fun `without a language callback the chip is hidden`() {
        welcome(onLanguage = null)
        compose.onNodeWithContentDescription("Language, English").assertDoesNotExist()
    }

    @Test
    fun `page one shows the language chip and no Restore button`() {
        welcome(onLanguage = {})
        compose.onNodeWithContentDescription("Language, English").assertIsDisplayed()
        compose.onNodeWithText("Restore").assertDoesNotExist()
        compose.onNodeWithContentDescription("Restore").assertDoesNotExist()
    }

    @Test
    fun `the removed switching card is gone`() {
        welcome(onLanguage = {})
        compose.onNodeWithText("Switching to uMessage?").assertDoesNotExist()
        compose.onNodeWithText("Restore a backup").assertDoesNotExist()
    }

    @Test
    fun `the purge row shows the days, lists the five choices and reports a pick`() {
        var days by mutableIntStateOf(30)
        compose.setContent {
            UMessageTheme(darkTheme = false, dynamicColor = false, syncWindow = false) {
                PurgeRow(days = days, onDays = { days = it })
            }
        }
        compose.onNodeWithText("Keep spam for").assertIsDisplayed()
        compose.onNodeWithText("30 days").performClick()
        listOf("7 days", "14 days", "90 days", "Never").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        compose.onNodeWithText("Never").performClick()
        assertEquals(0, days)
        compose.onNodeWithText("Never").assertIsDisplayed()
    }
}

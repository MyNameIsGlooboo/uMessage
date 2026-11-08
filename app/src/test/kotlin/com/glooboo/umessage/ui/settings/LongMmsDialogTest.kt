package com.glooboo.umessage.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.ui.theme.UMessageTheme
import com.google.common.truth.Truth.assertThat
import java.util.Locale
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Q9: the choice dialog of "Send long texts as picture message (MMS)". */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class LongMmsDialogTest {
    @get:Rule
    val compose = createComposeRule()

    private val chosen = mutableListOf<Int>()

    private fun setUp(selected: Int, locale: Locale = Locale.ENGLISH) = compose.setContent {
        UMessageTheme(darkTheme = false, dynamicColor = false, syncWindow = false) {
            LongMmsDialog(selected, locale, onChoose = { chosen += it }, onDismiss = {})
        }
    }

    @Test fun neverComesFirstThenTheNumbersOfMessages() {
        setUp(selected = 0)
        compose.onNodeWithText("Send long texts as picture message (MMS)").assertIsDisplayed()
        compose.onNodeWithText("Never (default)").assertIsDisplayed()
        compose.onNodeWithText("After 2 messages").assertExists()
        listOf(3, 4, 5, 6, 8, 10).forEach { compose.onNodeWithText("After $it messages").assertExists() }
    }

    @Test fun choosingNeverReportsZero() {
        setUp(selected = 4)
        compose.onNodeWithText("Never (default)").performClick()
        assertThat(chosen).containsExactly(0)
    }

    @Test fun choosingANumberReportsThatNumber() {
        setUp(selected = 0)
        compose.onNodeWithText("After 5 messages").performClick()
        assertThat(chosen).containsExactly(5)
    }

    @Test
    @Config(qualifiers = "fa-w411dp-h891dp")
    fun persianShowsPersianWordsAndDigits() {
        setUp(selected = 0, locale = Locale("fa"))
        compose.onNodeWithText("هرگز (پیش‌فرض)").assertExists()
        compose.onNodeWithText("بعد از ۳ پیام").assertExists()
    }
}

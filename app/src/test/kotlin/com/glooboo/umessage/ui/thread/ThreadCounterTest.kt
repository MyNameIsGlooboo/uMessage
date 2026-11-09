package com.glooboo.umessage.ui.thread

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** RM-46: the composer counter shows only when needed: long, multipart or MMS text (or the setting). */
@RunWith(AndroidJUnit4::class)
class ThreadCounterTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun eightyPersianCharactersShowTwoSms() {
        compose.setContent { Text(segmentLabel(SmsSegments.of("س".repeat(80)))) }
        compose.onNodeWithText("\u206654 · 2\u2069").assertExists()
    }

    @Test
    fun onePartShowsOnlyWhatIsLeft() {
        compose.setContent { Text(segmentLabel(SmsSegments.of("Hi"))) }
        compose.onNodeWithText("\u2066158\u2069").assertExists()
    }

    @Test
    fun shortMessagesShowNoCounter() {
        assertFalse(shouldShowCounter(50, SmsSegments.of("a".repeat(50)), isMms = false))
        assertFalse(shouldShowCounter(70, SmsSegments.of("س".repeat(70)), isMms = false))
        assertFalse(shouldShowCounter(0, null, isMms = false))
    }

    @Test
    fun counterShowsAfterHundredCharacters() {
        assertFalse(shouldShowCounter(99, SmsSegments.of("a".repeat(99)), isMms = false))
        assertTrue(shouldShowCounter(100, SmsSegments.of("a".repeat(100)), isMms = false))
    }

    @Test
    fun multipartPersianShowsCounterBeforeHundred() {
        assertTrue(shouldShowCounter(71, SmsSegments.of("س".repeat(71)), isMms = false))
    }

    @Test
    fun mmsAlwaysShowsCounter() {
        assertTrue(shouldShowCounter(5, SmsSegments.of("Hello"), isMms = true))
    }

    @Test
    fun settingShowsCounterForShortText() {
        assertTrue(shouldShowCounter(5, SmsSegments.of("Hello"), isMms = false, always = true))
        assertFalse(shouldShowCounter(0, null, isMms = false, always = true))
    }
}

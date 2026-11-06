package com.glooboo.umessage.ui.thread

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** RM-46: the composer counter shows parts once a text needs more than one SMS, even with the setting off. */
@RunWith(AndroidJUnit4::class)
class ThreadCounterTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun eightyPersianCharactersShowTwoSms() {
        compose.setContent { Text(segmentLabel(SmsSegments.of("س".repeat(80)))) }
        compose.onNodeWithText("54 · 2 SMS").assertExists()
    }

    @Test
    fun onePartShowsOnlyWhatIsLeft() {
        compose.setContent { Text(segmentLabel(SmsSegments.of("Hi"))) }
        compose.onNodeWithText("158").assertExists()
    }

    @Test
    fun multipartTextAlwaysShowsTheCounter() {
        val vm = ThreadViewModel(SavedStateHandle())
        vm.segments = SmsSegments.of("Hello")
        assertFalse(vm.counterNearLimit)

        vm.segments = SmsSegments.of("س".repeat(71))
        assertTrue(vm.counterNearLimit)
    }
}

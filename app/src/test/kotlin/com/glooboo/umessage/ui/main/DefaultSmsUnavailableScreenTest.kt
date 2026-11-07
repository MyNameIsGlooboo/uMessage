package com.glooboo.umessage.ui.main

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.ui.theme.UMessageTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** I22: a phone with no default-SMS dialog gets an explanation and a button to Android's default apps. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class DefaultSmsUnavailableScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the explanation names the default apps settings and its button reports a tap`() {
        var taps = 0
        compose.setContent {
            UMessageTheme(darkTheme = false, dynamicColor = false, syncWindow = false) {
                DefaultSmsRequiredScreen(onSetDefault = { taps++ }, onClose = {}, unavailable = true)
            }
        }
        compose.onNodeWithText("Open default apps").assertIsDisplayed().performClick()
        compose.onNodeWithText("Set as default").assertDoesNotExist()
        assertEquals(1, taps)
    }
}

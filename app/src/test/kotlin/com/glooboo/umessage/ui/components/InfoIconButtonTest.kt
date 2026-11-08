package com.glooboo.umessage.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InfoIconButtonTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theIconOpensTheTextUnderTheRowNameAndCloseHidesIt() {
        compose.setContent { MaterialTheme { InfoIconButton("Daily limit", "Forwarding sends SMS on your plan.") } }

        compose.onNodeWithText("Forwarding sends SMS on your plan.").assertDoesNotExist()
        compose.onNodeWithContentDescription("More information").assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()

        compose.onNodeWithText("Daily limit").assertIsDisplayed()
        compose.onNodeWithText("Forwarding sends SMS on your plan.").assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("Forwarding sends SMS on your plan.").assertDoesNotExist()
    }
}

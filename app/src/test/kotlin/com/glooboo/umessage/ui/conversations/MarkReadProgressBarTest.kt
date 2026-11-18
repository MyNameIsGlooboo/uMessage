package com.glooboo.umessage.ui.conversations

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MarkReadProgressBarTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theBarShowsTheCountsWhileWorkingAndIsGoneAfterwards() {
        var progress by mutableStateOf<MarkReadProgress?>(MarkReadProgress(50, 120))
        compose.setContent {
            MaterialTheme { progress?.let { MarkReadProgressBar(it) } }
        }

        compose.onNodeWithTag(MARK_READ_PROGRESS_TAG).assertIsDisplayed()
        compose.onNodeWithText("Marking chats as read: 50 of 120").assertIsDisplayed()

        progress = MarkReadProgress(120, 120)
        compose.waitForIdle()
        compose.onNodeWithText("Marking chats as read: 120 of 120").assertIsDisplayed()

        progress = null
        compose.waitForIdle()
        compose.onNodeWithTag(MARK_READ_PROGRESS_TAG).assertDoesNotExist()
    }
}

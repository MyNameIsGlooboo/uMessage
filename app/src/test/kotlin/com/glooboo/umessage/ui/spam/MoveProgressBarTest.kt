package com.glooboo.umessage.ui.spam

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.ui.conversations.MarkReadProgress
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MoveProgressBarTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theBarShowsTheCountsForABigBatchAndIsGoneAfterwards() {
        var progress by mutableStateOf<MarkReadProgress?>(MarkReadProgress(12, 80))
        compose.setContent {
            MaterialTheme { progress?.let { MoveProgressBar(it) } }
        }

        compose.onNodeWithTag(MOVE_PROGRESS_TAG).assertIsDisplayed()
        compose.onNodeWithText("Moving messages: 12 of 80").assertIsDisplayed()

        progress = null
        compose.waitForIdle()
        compose.onNodeWithTag(MOVE_PROGRESS_TAG).assertDoesNotExist()
    }
}

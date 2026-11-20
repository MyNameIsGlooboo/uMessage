package com.glooboo.umessage.ui.conversations

import android.app.Application
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.R
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Provider truth, step C (C-2, C-5): snackbar windows close when cancelled; the Archived S14 message shows. */
@RunWith(AndroidJUnit4::class)
class SnackbarWindowTest {

    @get:Rule
    val compose = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun aCancelledSnackbarCommitsItsWindow(): Unit = runBlocking {
        var committed = 0
        val job = launch { commitIfCancelled({ committed++ }) { awaitCancellation() } }
        yield()
        job.cancel()
        job.join()
        assertThat(committed).isEqualTo(1)
    }

    @Test
    fun anAnsweredSnackbarIsNotCommittedTwice(): Unit = runBlocking {
        var committed = 0
        commitIfCancelled({ committed++ }) { }
        assertThat(committed).isEqualTo(0)
    }

    @Test
    fun theArchivedDeleteMessageShowsBeforeItIsCleared() {
        var cleared = 0
        // The snackbar's own timeout must not pass while the test looks (virtual time stands still).
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val snackbar = remember { SnackbarHostState() }
            var message by remember { mutableStateOf<Int?>(R.string.pt_delete_not_default) }
            DeleteMessageEffect(message, snackbar) {
                cleared++
                message = null
            }
            SnackbarHost(snackbar)
        }
        repeat(FRAMES) { compose.mainClock.advanceTimeByFrame() }
        compose.onNodeWithText(app.getString(R.string.pt_delete_not_default)).assertIsDisplayed()
        assertThat(cleared).isEqualTo(0)
    }

    private companion object {
        const val FRAMES = 10
    }
}

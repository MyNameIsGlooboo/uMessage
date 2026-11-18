package com.glooboo.umessage.ui.spam

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.ui.theme.UMessageTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The Blocked search box closes when nothing is held any more, and stays open through a rotation. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h2000dp")
class BlockedSearchResetTest {
    @get:Rule
    val compose = createComposeRule()

    private val close = "Close search"

    private val held = BlockedMessage(
        1, "+15550001234", "hello", 1, false, System.currentTimeMillis(), DecisionReason.UNKNOWN_SENDER,
    )

    private fun withHeld(query: String) =
        BlockedUiState(loading = false, groups = groupBySender(listOf(held)), query = query)

    @Test
    fun `the search box closes when the last held message goes and stays closed when one comes back`() {
        var state by mutableStateOf(withHeld(query = "hel"))
        var cleared = false
        compose.setContent {
            UMessageTheme(darkTheme = false, dynamicColor = false, syncWindow = false) {
                BlockedScreen(state, SnackbarHostState(), BlockedActions(onSetQuery = { cleared = it.isEmpty() }))
            }
        }
        compose.onNodeWithContentDescription(close).assertExists()
        state = BlockedUiState(loading = false, query = "hel")
        compose.waitForIdle()
        assertThat(cleared).isTrue()
        state = withHeld(query = "")
        compose.waitForIdle()
        compose.onNodeWithContentDescription(close).assertDoesNotExist()
    }

    @Test
    fun `a search box left open on another tab stays closed when the tab comes back`() {
        var show by mutableStateOf(true)
        var leaves by mutableIntStateOf(0)
        var state by mutableStateOf(withHeld(query = "hel"))
        compose.setContent {
            val holder = rememberSaveableStateHolder()
            UMessageTheme(darkTheme = false, dynamicColor = false, syncWindow = false) {
                if (show) {
                    holder.SaveableStateProvider("spam") {
                        BlockedScreen(state, SnackbarHostState(), BlockedActions(), tabLeaves = leaves)
                    }
                }
            }
        }
        compose.onNodeWithContentDescription(close).assertExists()
        show = false
        compose.waitForIdle()
        // BlockedRoute's leave: the query is cleared and the leave is counted.
        state = withHeld(query = "")
        leaves++
        show = true
        compose.waitForIdle()
        compose.onNodeWithContentDescription(close).assertDoesNotExist()
    }

    @Test
    fun `an open search box survives a rotation`() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            UMessageTheme(darkTheme = false, dynamicColor = false, syncWindow = false) {
                BlockedScreen(withHeld(query = "hel"), SnackbarHostState(), BlockedActions())
            }
        }
        compose.onNodeWithContentDescription(close).assertExists()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithContentDescription(close).assertExists()
    }
}

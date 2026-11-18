package com.glooboo.umessage.ui.spam

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.UnknownSenderMode
import com.glooboo.umessage.ui.theme.UMessageTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The empty Spam tab shows the sorting card in the current mode, and drops it once a message is held. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h2000dp")
class BlockedEmptyHowTest {
    @get:Rule
    val compose = createComposeRule()

    private val cardTag = "blocked_empty_how"

    private fun show(state: BlockedUiState) = compose.setContent {
        UMessageTheme(darkTheme = false, dynamicColor = false, syncWindow = false) {
            BlockedScreen(state, SnackbarHostState(), BlockedActions())
        }
    }

    private fun inCard(text: String) = hasText(text) and hasAnyAncestor(hasTestTag(cardTag))

    @Test
    fun `the empty tab shows its text and the card`() {
        show(BlockedUiState(loading = false))
        compose.onNodeWithText("Nothing in Spam yet").assertIsDisplayed()
        compose.onNodeWithTag(cardTag).assertExists()
    }

    @Test
    fun `with a held message the card is gone`() {
        val message = BlockedMessage(
            1, "+15550001234", "hello", 1, false, System.currentTimeMillis(), DecisionReason.UNKNOWN_SENDER,
        )
        show(BlockedUiState(loading = false, groups = groupBySender(listOf(message))))
        compose.onNodeWithTag(cardTag).assertDoesNotExist()
    }

    @Test
    fun `in Strict the card lists the code and sends two rows to Spam`() {
        show(BlockedUiState(loading = false, mode = UnknownSenderMode.STRICT))
        compose.onNodeWithText("Verification code", useUnmergedTree = true).assertExists()
        compose.onAllNodes(inCard("Spam"), useUnmergedTree = true).assertCountEquals(2)
    }
}

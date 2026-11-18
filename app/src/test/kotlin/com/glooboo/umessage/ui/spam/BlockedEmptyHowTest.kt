package com.glooboo.umessage.ui.spam

import android.Manifest
import android.app.Application
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.UnknownSenderMode
import com.glooboo.umessage.ui.theme.UMessageTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The empty Spam tab shows the sorting card in the current mode, and drops it once a message is held. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h2000dp")
class BlockedEmptyHowTest {
    @get:Rule
    val compose = createComposeRule()

    private val cardTag = "blocked_empty_how"

    @Before
    fun forgetThatTheCardPlayed() = resetEmptyCardPlayed()

    private fun show(state: BlockedUiState) = compose.setContent {
        UMessageTheme(darkTheme = false, dynamicColor = false, syncWindow = false) {
            BlockedScreen(state, SnackbarHostState(), BlockedActions())
        }
    }

    private fun grantContacts() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(Manifest.permission.READ_CONTACTS)
    }

    private fun inCard(text: String) = hasText(text) and hasAnyAncestor(hasTestTag(cardTag))

    @Test
    fun `the empty tab shows its text and the card`() {
        grantContacts()
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
        grantContacts()
        show(BlockedUiState(loading = false, mode = UnknownSenderMode.STRICT))
        compose.onNodeWithText("Verification code", useUnmergedTree = true).assertExists()
        compose.onAllNodes(inCard("Spam"), useUnmergedTree = true).assertCountEquals(2)
    }

    @Test
    fun `without contacts the card is gone in Smart`() {
        show(BlockedUiState(loading = false, mode = UnknownSenderMode.SMART))
        compose.onNodeWithTag(cardTag).assertDoesNotExist()
    }

    @Test
    fun `without contacts the card is gone in Strict`() {
        show(BlockedUiState(loading = false, mode = UnknownSenderMode.STRICT))
        compose.onNodeWithTag(cardTag).assertDoesNotExist()
    }

    @Test
    fun `without contacts the card stays in Off`() {
        show(BlockedUiState(loading = false, mode = UnknownSenderMode.OFF))
        compose.onNodeWithTag(cardTag).assertExists()
    }

    @Test
    fun `in a search with no match the card is gone`() {
        grantContacts()
        show(BlockedUiState(loading = false, heldCount = 3, query = "nothing like this"))
        compose.onNodeWithTag(cardTag).assertDoesNotExist()
        compose.onNodeWithText("Nothing in Spam yet").assertDoesNotExist()
    }

    @Test
    fun `in a chip with no match the card is gone`() {
        grantContacts()
        show(BlockedUiState(loading = false, heldCount = 3, filter = BlockedFilter.HasLink))
        compose.onNodeWithTag(cardTag).assertDoesNotExist()
        compose.onNodeWithText("Nothing in Spam yet").assertDoesNotExist()
    }

    @Test
    fun `the card plays once per app open and a second visit still shows it`() {
        grantContacts()
        var visible by mutableStateOf(true)
        compose.setContent {
            UMessageTheme(darkTheme = false, dynamicColor = false, syncWindow = false) {
                if (visible) BlockedScreen(BlockedUiState(loading = false), SnackbarHostState(), BlockedActions())
            }
        }
        compose.waitForIdle()
        assertTrue(emptyCardPlayed)
        visible = false
        compose.waitForIdle()
        compose.onNodeWithTag(cardTag).assertDoesNotExist()
        visible = true
        compose.waitForIdle()
        assertTrue(emptyCardPlayed)
        compose.onNodeWithTag(cardTag).assertExists()
    }

    @Test
    fun `the played flag starts false after a reset`() {
        resetEmptyCardPlayed()
        assertFalse(emptyCardPlayed)
    }
}

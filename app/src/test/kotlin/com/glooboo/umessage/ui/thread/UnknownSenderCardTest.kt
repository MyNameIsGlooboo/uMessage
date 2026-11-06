package com.glooboo.umessage.ui.thread

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.R
import com.glooboo.umessage.ui.theme.UMessageTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** I04: the card's three buttons and its close button. */
@RunWith(AndroidJUnit4::class)
class UnknownSenderCardTest {

    @get:Rule
    val compose = createComposeRule()

    private fun str(id: Int) = ApplicationProvider.getApplicationContext<android.content.Context>().getString(id)

    @Test
    fun buttonsCallTheirActions() {
        val calls = mutableListOf<String>()
        compose.setContent {
            UMessageTheme {
                UnknownSenderCard(
                    caution = false,
                    onAddContact = { calls += "add" },
                    onAlwaysAllow = { calls += "allow" },
                    onBlock = { calls += "block" },
                    onDismiss = { calls += "dismiss" },
                )
            }
        }
        compose.onNodeWithText(str(R.string.i04_card_add_contact)).performClick()
        compose.onNodeWithText(str(R.string.i04_card_allow)).performClick()
        compose.onNodeWithText(str(R.string.i04_card_block)).performClick()
        compose.onNodeWithContentDescription(str(R.string.i04_card_dismiss)).performClick()
        assertThat(calls).containsExactly("add", "allow", "block", "dismiss").inOrder()
    }

    private fun show(caution: Boolean) = compose.setContent {
        UMessageTheme {
            UnknownSenderCard(caution, onAddContact = null, onAlwaysAllow = null, onBlock = {}, onDismiss = {})
        }
    }

    @Test
    fun cautionLineShowsOnlyWhenAsked() {
        show(caution = false)
        compose.onNodeWithText(str(R.string.i04_card_caution)).assertDoesNotExist()
    }

    @Test
    fun cautionLineShowsWhenAsked() {
        show(caution = true)
        compose.onNodeWithText(str(R.string.i04_card_caution)).assertExists()
    }

    @Test
    fun buttonsWithoutAnActionAreLeftOut() {
        show(caution = false)
        compose.onNodeWithText(str(R.string.i04_card_add_contact)).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.i04_card_allow)).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.i04_card_block)).assertExists()
    }
}

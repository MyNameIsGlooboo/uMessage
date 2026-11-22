package com.glooboo.umessage.ui.spam

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.R
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamRule
import com.glooboo.umessage.ui.theme.UMessageTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The "Always allow this name?" dialog and the pure rules behind it. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class NameAllowDialogTest {

    @get:Rule
    val compose = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun show(names: List<String>, onAllow: () -> Unit = {}, onDismiss: () -> Unit = {}) =
        compose.setContent {
            UMessageTheme(darkTheme = false, dynamicColor = false, syncWindow = false) {
                NameAllowDialog(names, onAllow, onDismiss)
            }
        }

    @Test
    fun theDialogNamesTheSenderAndAlwaysAllowCallsBack() {
        var allowed = 0
        var dismissed = 0
        show(listOf(NAME), onAllow = { allowed++ }, onDismiss = { dismissed++ })
        compose.onNodeWithText(app.getString(R.string.nameallow_title)).assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.nameallow_body, NAME)).assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.nameallow_allow)).performClick()
        assertThat(allowed).isEqualTo(1)
        assertThat(dismissed).isEqualTo(0)
    }

    @Test
    fun cancelCallsDismissOnly() {
        var allowed = 0
        var dismissed = 0
        show(listOf(NAME), onAllow = { allowed++ }, onDismiss = { dismissed++ })
        compose.onNodeWithText(app.getString(R.string.spam_cancel)).performClick()
        assertThat(dismissed).isEqualTo(1)
        assertThat(allowed).isEqualTo(0)
    }

    @Test
    fun theBodyListsUpToThreeNamesAndCountsTheRest() {
        val names = listOf("AA-BANK", "BB-BANK", "CC-BANK", "DD-BANK", "EE-BANK")
        val body = nameAllowBody(app.resources, names)
        assertThat(body).contains("AA-BANK, BB-BANK, CC-BANK")
        assertThat(body).contains("2")
        assertThat(body).doesNotContain("DD-BANK")
        assertThat(nameAllowBody(app.resources, names.take(2))).contains("AA-BANK, BB-BANK")
    }

    @Test
    fun onlyUnallowedNamesOfMovedMessagesAreOffered() {
        val rules = listOf(SpamRule(1, ListKind.ALLOW, RuleType.SENDER_ID, "bank-x"))
        val offered = namesToOffer(listOf("BANK-X", "Shop", "shop", NUMBER, " "), rules)
        assertThat(offered).containsExactly("Shop")
        assertThat(namesToOffer(listOf(NUMBER), emptyList())).isEmpty()
        assertThat(namesToOffer(emptyList(), emptyList())).isEmpty()
    }

    @Test
    fun aRuleWithoutADashAllowsTheNameWithAPrefixLikeTheFilterDoes() {
        val rules = listOf(SpamRule(1, ListKind.ALLOW, RuleType.SENDER_ID, "HDFCBK"))
        assertThat(namesToOffer(listOf("VM-HDFCBK", "VM-OTHER"), rules)).containsExactly("VM-OTHER")
    }

    @Test
    fun aRuleForOneSimOnlyDoesNotCountAsAllowing() {
        val oneSim = SpamRule(1, ListKind.ALLOW, RuleType.SENDER_ID, "BANK-X", subscriptionId = 1)
        val block = SpamRule(2, ListKind.BLOCK, RuleType.SENDER_ID, "BANK-X")
        assertThat(namesToOffer(listOf("BANK-X"), listOf(oneSim, block))).containsExactly("BANK-X")
    }

    private companion object {
        const val NAME = "BANK-X"
        const val NUMBER = "+15550001234"
    }
}

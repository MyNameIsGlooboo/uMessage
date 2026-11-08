package com.glooboo.umessage.ui.spam

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamRule
import com.glooboo.umessage.spam.engine.NumberNormalizer
import com.glooboo.umessage.ui.theme.UMessageTheme
import com.google.common.truth.Truth.assertThat
import org.fossify.commons.models.PhoneNumber
import org.fossify.commons.models.SimpleContact
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Choose from contacts: number extraction, de-duplication, rule creation and the picker list. */
@RunWith(AndroidJUnit4::class)
class ContactPicksTest {

    @get:Rule
    val compose = createComposeRule()

    private val normalizer = NumberNormalizer { "US" }

    private fun contact(id: Int, name: String, vararg numbers: String) = SimpleContact(
        rawId = id,
        contactId = id,
        name = name,
        photoUri = "",
        phoneNumbers = numbers.mapTo(ArrayList()) { PhoneNumber(it, 0, "", it) },
        birthdays = ArrayList(),
        anniversaries = ArrayList(),
    )

    private val anna = contact(1, "Anna Berg", "(415) 555-0101")
    private val ben = contact(2, "Ben", "415-555-0102", "+1 415 555 0103", "4155550102")
    private val all = listOf(anna, ben)

    @Test
    fun numbersAreNormalisedAndRepeatsOfOneNumberAreDropped() {
        assertThat(ContactNumberPicks.numbersOf(anna, normalizer).map { it.number }).containsExactly("+14155550101")
        val ben = ContactNumberPicks.numbersOf(ben, normalizer)
        assertThat(ben.map { it.number }).containsExactly("+14155550102", "+14155550103").inOrder()
        assertThat(ben.map { it.name }.toSet()).containsExactly("Ben")
    }

    @Test
    fun blankNumbersGiveNothing() {
        assertThat(ContactNumberPicks.numbersOf(contact(3, "Nobody", " "), normalizer)).isEmpty()
    }

    @Test
    fun searchMatchesNamesAndDigitsOfAnyNumber() {
        assertThat(ContactNumberPicks.matching(all, "anna")).containsExactly(anna)
        assertThat(ContactNumberPicks.matching(all, "555 0103")).containsExactly(ben)
        assertThat(ContactNumberPicks.matching(all, "  ")).containsExactlyElementsIn(all).inOrder()
        assertThat(ContactNumberPicks.matching(all, "zzz")).isEmpty()
    }

    @Test
    fun theSameNumberPickedTwiceCountsOnce() {
        val picks = listOf(
            PickedNumber("Ben", "+14155550102"),
            PickedNumber("Ben again", "+14155550102"),
            PickedNumber("Anna", "+14155550101"),
        )
        assertThat(ContactNumberPicks.distinct(picks).map { it.number })
            .containsExactly("+14155550102", "+14155550101").inOrder()
    }

    @Test
    fun oneRulePerNumberSkippingNumbersThatAlreadyHaveARuleInEitherList() {
        val existing = listOf(
            SpamRule(id = 1, kind = ListKind.BLOCK, type = RuleType.NUMBER, pattern = "+14155550101"),
            SpamRule(id = 2, kind = ListKind.ALLOW, type = RuleType.KEYWORD, pattern = "bank"),
        )
        val picks = listOf(
            PickedNumber("Anna", "+14155550101"),
            PickedNumber("Ben", "+14155550102"),
            PickedNumber("Ben", "+14155550102"),
            PickedNumber("Odd", "ab"),
        )
        val batch = ContactNumberPicks.plan(ListKind.ALLOW, subscriptionId = 3, existing = existing, picks = picks)
        assertThat(batch.rules.map { it.pattern }).containsExactly("+14155550102")
        assertThat(batch.rules.single().kind).isEqualTo(ListKind.ALLOW)
        assertThat(batch.rules.single().type).isEqualTo(RuleType.NUMBER)
        assertThat(batch.rules.single().subscriptionId).isEqualTo(3)
        assertThat(batch.rules.single().note).isEqualTo("Ben")
        assertThat(batch.skipped).isEqualTo(3)
    }

    private var picked: List<PickedNumber>? = null

    private fun showPicker(multi: Boolean) {
        picked = null
        compose.setContent {
            UMessageTheme {
                ContactPickerContent(all, normalizer, multi, onPick = { picked = it }, onDismiss = {})
            }
        }
    }

    @Test
    fun severalContactsCanBeTickedAndAddedAtOnce() {
        showPicker(multi = true)
        compose.onNodeWithTag("contact_pick_confirm").assertIsNotEnabled()

        compose.onNodeWithText("Anna Berg").performClick()
        compose.onNodeWithText("Ben").performClick()
        compose.onAllNodesWithTag("contact_number")[1].performScrollTo().performClick()
        compose.onNodeWithTag("contact_pick_confirm").assertIsEnabled().performClick()

        assertThat(picked!!.map { it.number }).containsExactly("+14155550101", "+14155550103").inOrder()
    }

    @Test
    fun aSingleModeTapHandsBackThatOneNumber() {
        showPicker(multi = false)
        compose.onNodeWithText("Anna Berg").performClick()
        assertThat(picked).containsExactly(PickedNumber("Anna Berg", "+14155550101"))
    }

    @Test
    fun searchNarrowsTheListAndAnEmptyResultSaysSo() {
        showPicker(multi = true)
        compose.onNodeWithTag("contact_search").performTextInput("ben")
        assertThat(compose.onAllNodesWithTag("contact_row").fetchSemanticsNodes()).hasSize(1)

        compose.onNodeWithTag("contact_search").performTextInput("zzz")
        compose.onNodeWithText("No contacts with a phone number found").assertIsDisplayed()
    }

    @Test
    fun theChooseButtonIsOnTheForwardEditorNextToTheSender() {
        compose.setContent {
            UMessageTheme { ForwardSheet(ForwardForm(), emptyList(), ForwardActions()) }
        }
        compose.onNodeWithTag("choose_from_contacts").assertIsDisplayed()
    }
}

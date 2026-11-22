package com.glooboo.umessage.ui.onboarding

import android.app.Application
import android.os.Looper
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.R
import com.glooboo.umessage.spam.SpamRepository
import com.glooboo.umessage.spam.SpamSettings
import com.glooboo.umessage.spam.UnknownSenderMode
import com.glooboo.umessage.ui.spam.FakeSpamRepository
import com.glooboo.umessage.ui.theme.UMessageTheme
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Provider truth (4.6, U12): Welcome has a two-option filter choice under the sorting card, the card follows it, the
 * mode page and "Keep spam for" are gone, and the choice is stored as the filter switch.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class WelcomeFilterChoiceTest {

    @get:Rule
    val compose = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val res = app.resources

    private fun page(initial: Boolean = true, onChange: (Boolean) -> Unit = {}) = compose.setContent {
        UMessageTheme(darkTheme = false, dynamicColor = false, syncWindow = false) {
            var on by remember { mutableStateOf(initial) }
            WelcomePage(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                reduceMotion = true,
                filterOn = on,
                onFilter = {
                    on = it
                    onChange(it)
                },
            )
        }
    }

    @Test
    fun theChoiceShowsBothOptionsWithTheFirstRecommendedAndChosen() {
        page()
        compose.onNodeWithText(res.getString(R.string.pt_onb_filter_on)).performScrollTo()
        compose.onNodeWithTag(FILTER_ON_TAG).assertIsSelected()
        compose.onNodeWithTag(FILTER_OFF_TAG).assertIsNotSelected()
        compose.onNodeWithText(res.getString(R.string.pt_onb_filter_on_desc)).assertExists()
        compose.onNodeWithText(res.getString(R.string.pt_onb_filter_off_desc)).assertExists()
        compose.onNodeWithText(res.getString(R.string.pt_onb_filter_hint)).assertExists()
        compose.onNodeWithText(res.getString(R.string.settings_nav_recommended), useUnmergedTree = true).assertExists()
    }

    @Test
    fun choosingKeepEverythingChangesTheCardAndItsSentence() {
        var chosen: Boolean? = null
        page(onChange = { chosen = it })
        val sentenceOn = res.getString(R.string.onb_welcome_demo_desc)
        val sentenceOff = res.getString(R.string.pt_onb_demo_desc_off)
        compose.onNode(hasContentDescription(sentenceOn)).assertExists()

        compose.onNodeWithTag(FILTER_OFF_TAG).performScrollTo().performClick()
        assertThat(chosen).isFalse()
        compose.onNodeWithTag(FILTER_OFF_TAG).assertIsSelected()
        compose.onNode(hasContentDescription(sentenceOff)).assertExists()
        compose.onNode(hasContentDescription(sentenceOn)).assertDoesNotExist()
    }

    @Test
    fun theCardHasASentenceForBothStates() {
        assertThat(sortingCardSentence(UnknownSenderMode.SMART)).isEqualTo(R.string.onb_welcome_demo_desc)
        assertThat(sortingCardSentence(UnknownSenderMode.OFF)).isEqualTo(R.string.pt_onb_demo_desc_off)
    }

    @Test
    fun theModePageLeavesTheFlow() {
        assertThat(OnboardingState.pagesFor(allowNeeded = true))
            .containsExactly(OnboardingPage.WELCOME, OnboardingPage.ALLOW, OnboardingPage.KEEP_RUNNING).inOrder()
        assertThat(OnboardingState.pagesFor(allowNeeded = false))
            .containsExactly(OnboardingPage.WELCOME, OnboardingPage.KEEP_RUNNING).inOrder()
    }

    @Test
    fun theChoiceIsStoredAsTheFilterSwitchAndNoAutoDeleteDaysAreWritten() {
        val repo = FakeSpamRepository(
            initialBlocked = emptyList(),
            initialRules = emptyList(),
            initialSettings = SpamSettings(purgeAfterDays = 0),
        )
        val vm = OnboardingViewModel(app) { repo }
        idleMain()
        assertThat(vm.filterOn.value).isTrue()

        vm.setFilterOn(false)
        awaitUntil { !runBlocking { repo.settings.first() }.filterOn }
        assertThat(vm.filterOn.value).isFalse()

        // S13: Welcome never writes purgeAfterDays.
        assertThat(runBlocking { repo.settings.first() }.purgeAfterDays).isEqualTo(0)
    }

    @Test
    fun theStoredSwitchIsShownWhenWelcomeOpensAgain() {
        val repo = FakeSpamRepository(
            initialBlocked = emptyList(),
            initialRules = emptyList(),
            initialSettings = SpamSettings(filterOn = false),
        )
        val vm = OnboardingViewModel(app) { repo }
        awaitUntil { !vm.filterOn.value }
    }

    @Test
    fun aTappedChoiceIsNotUndoneByTheSettingsReadThatEndsLater() {
        val gate = CompletableDeferred<Unit>()
        val fake = FakeSpamRepository(initialBlocked = emptyList(), initialRules = emptyList())
        val slow = object : SpamRepository by fake {
            override val settings: Flow<SpamSettings> = flow {
                gate.await()
                emit(SpamSettings(filterOn = true))
            }
        }
        val vm = OnboardingViewModel(app) { slow }
        vm.setFilterOn(false)
        gate.complete(Unit)
        idleMain()
        assertThat(vm.filterOn.value).isFalse()
    }

    @Test
    fun aFailedWriteIsSaidOnThePage() {
        val fake = FakeSpamRepository(initialBlocked = emptyList(), initialRules = emptyList())
        val broken = object : SpamRepository by fake {
            override suspend fun updateSettings(transform: (SpamSettings) -> SpamSettings) = error("disk full")
        }
        val vm = OnboardingViewModel(app) { broken }
        vm.setFilterOn(false)
        awaitUntil { vm.filterSaveFailed.value }

        compose.setContent {
            UMessageTheme(darkTheme = false, dynamicColor = false, syncWindow = false) {
                WelcomeFilterChoice(filterOn = false, onFilter = {}, saveFailed = true)
            }
        }
        compose.onNodeWithText(res.getString(R.string.spam_snack_failed)).assertExists()
    }

    private fun idleMain() = shadowOf(Looper.getMainLooper()).idle()

    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (true) {
            idleMain()
            if (condition()) return
            check(System.currentTimeMillis() < deadline) { "condition not met" }
            Thread.sleep(10)
        }
    }
}

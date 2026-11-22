package com.glooboo.umessage.ui.onboarding

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.R
import com.glooboo.umessage.helpers.CleanupPrefs
import com.glooboo.umessage.spam.SpamRepository
import com.glooboo.umessage.spam.SpamSettings
import com.glooboo.umessage.spam.data.SpamSettingsStore
import com.glooboo.umessage.ui.spam.FakeSpamRepository
import com.glooboo.umessage.ui.theme.UMessageTheme
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * J16: the Welcome page has the quiet Spam notification switch (off on a new install), the line about checking Spam
 * and the weekly summary switch (on on a new install). They are the settings Settings shows, and an update keeps
 * what it had.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class WelcomeSpamNoticesTest {

    @get:Rule
    val compose = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val res = app.resources

    /** The spam settings Settings reads, behind a repository that stores through them like the real one. */
    private val store = SpamSettingsStore(app)
    private val spamPrefs = app.getSharedPreferences("spamless_spam_settings", Context.MODE_PRIVATE)
    private val repo: SpamRepository = object : SpamRepository by FakeSpamRepository(
        initialBlocked = emptyList(),
        initialRules = emptyList(),
    ) {
        override val settings: Flow<SpamSettings> = store.observe()
        override suspend fun updateSettings(transform: (SpamSettings) -> SpamSettings) = store.update(transform)
    }

    @Before
    fun freshInstall() {
        listOf("spamless_spam_settings", "spamless_onboarding", "umessage_install", "umessage_cleanup").forEach {
            app.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test
    fun aNewInstallStartsWithTheNotificationOffAndTheSummaryOn() {
        val vm = OnboardingViewModel(app) { repo }
        awaitUntil { CleanupPrefs.get(app).digestChosen }

        assertThat(vm.notifyOnBlock.value).isFalse()
        assertThat(vm.digestOn.value).isTrue()
        assertThat(store.get().notifyOnBlock).isFalse()
        assertThat(CleanupPrefs.get(app).digestOn).isTrue()
        // Opening Welcome writes no spam setting.
        assertThat(spamPrefs.contains("notify_on_block")).isFalse()
    }

    @Test
    fun anUpdateKeepsTheStoredValues() {
        // An older install: Welcome was finished, the defaults were applied, the user chose both values.
        OnboardingState.setCompleted(app)
        app.getSharedPreferences("umessage_install", Context.MODE_PRIVATE).edit()
            .putBoolean("defaults_applied", true).commit()
        CleanupPrefs.get(app).digestOn = false
        store.update { it.copy(notifyOnBlock = true) }

        val vm = OnboardingViewModel(app) { repo }
        awaitUntil { vm.notifyOnBlock.value && !vm.digestOn.value }

        assertThat(CleanupPrefs.get(app).digestOn).isFalse()
        assertThat(store.get().notifyOnBlock).isTrue()
    }

    @Test
    fun anOlderInstallThatNeverStoredTheSummaryKeepsItOn() {
        OnboardingState.setCompleted(app)

        val vm = OnboardingViewModel(app) { repo }
        awaitUntil { CleanupPrefs.get(app).digestChosen }

        assertThat(vm.digestOn.value).isTrue()
        assertThat(CleanupPrefs.get(app).digestOn).isTrue()
        assertThat(store.get().notifyOnBlock).isFalse()
    }

    @Test
    fun togglingOnWelcomeWritesTheSettingsThatSettingsShows() {
        val vm = OnboardingViewModel(app) { repo }
        idleMain()
        compose.setContent {
            UMessageTheme(darkTheme = false, dynamicColor = false, syncWindow = false) {
                val notifyOn by vm.notifyOnBlock.collectAsStateWithLifecycle()
                val digestOn by vm.digestOn.collectAsStateWithLifecycle()
                WelcomePage(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    reduceMotion = true,
                    onFilter = {},
                    notifyOn = notifyOn,
                    onNotify = vm::setNotifyOnBlock,
                    digestOn = digestOn,
                    onDigest = vm::setDigestOn,
                )
            }
        }

        awaitUntil { CleanupPrefs.get(app).digestChosen }
        assertThat(spamPrefs.contains("notify_on_block")).isFalse()

        compose.onNodeWithText(res.getString(R.string.digest_row_title)).performScrollTo().performClick()
        assertThat(CleanupPrefs.get(app).digestOn).isFalse()
        compose.onNodeWithText(res.getString(R.string.digest_row_title)).assertIsOff()
        // The summary switch is not the notification setting: notify_on_block is still unwritten.
        idleMain()
        assertThat(spamPrefs.contains("notify_on_block")).isFalse()

        compose.onNodeWithText(res.getString(R.string.onb_spam_notify_quiet)).performScrollTo().performClick()
        awaitUntil { store.get().notifyOnBlock }
        assertThat(spamPrefs.getBoolean("notify_on_block", false)).isTrue()
        compose.onNodeWithText(res.getString(R.string.onb_spam_notify_quiet)).assertIsOn()

        compose.onNodeWithText(res.getString(R.string.onb_spam_notify_quiet)).performScrollTo().performClick()
        awaitUntil { !store.get().notifyOnBlock }
        assertThat(vm.notifySaveFailed.value).isFalse()
    }

    @Test
    fun aFailedNotificationSaveIsSaidUnderThatSwitch() {
        val broken = object : SpamRepository by repo {
            override suspend fun updateSettings(transform: (SpamSettings) -> SpamSettings) = error("disk full")
        }
        val vm = OnboardingViewModel(app) { broken }
        compose.setContent {
            UMessageTheme(darkTheme = false, dynamicColor = false, syncWindow = false) {
                val notifyOn by vm.notifyOnBlock.collectAsStateWithLifecycle()
                val failed by vm.notifySaveFailed.collectAsStateWithLifecycle()
                WelcomeSpamNotices(notifyOn, vm::setNotifyOnBlock, true, {}, notifySaveFailed = failed)
            }
        }
        compose.onNodeWithText(res.getString(R.string.spam_snack_failed)).assertDoesNotExist()

        compose.onNodeWithText(res.getString(R.string.onb_spam_notify_quiet)).performClick()
        awaitUntil { vm.notifySaveFailed.value }

        assertThat(vm.filterSaveFailed.value).isFalse()
        compose.onNodeWithText(res.getString(R.string.spam_snack_failed)).assertIsDisplayed()
        assertThat(spamPrefs.contains("notify_on_block")).isFalse()
    }

    @Test
    fun theLineAndBothSwitchesAreShownWithTheirRoles() {
        compose.setContent { PageContent() }
        compose.onNodeWithText(res.getString(R.string.onb_spam_check_weekly)).performScrollTo().assertIsDisplayed()
        listOf(R.string.onb_spam_notify_quiet, R.string.digest_row_title).forEach { key ->
            compose.onNodeWithText(res.getString(key)).performScrollTo()
                .assertIsDisplayed()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
        }
        compose.onNodeWithText(res.getString(R.string.onb_spam_notify_quiet)).assertIsOff()
        compose.onNodeWithText(res.getString(R.string.digest_row_title)).assertIsOn()
        compose.onNodeWithText(res.getString(R.string.settings_nav_notify_body)).assertExists()
    }

    @Test
    @Config(qualifiers = "fa-w360dp-h800dp")
    fun inPersianAtTwiceTheFontSizeTheTextsAreShownAndTheSwitchesWork() {
        var notify: Boolean? = null
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                PageContent(onNotify = { notify = it })
            }
        }
        assertThat(res.getString(R.string.onb_spam_check_weekly)).contains("اسپم")
        listOf(R.string.onb_spam_notify_quiet, R.string.onb_spam_check_weekly, R.string.digest_row_title).forEach {
            compose.onNodeWithText(res.getString(it), useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithText(res.getString(R.string.onb_spam_notify_quiet)).performScrollTo().performClick()
        assertThat(notify).isTrue()
    }

    @Composable
    private fun PageContent(onNotify: (Boolean) -> Unit = {}) {
        UMessageTheme(darkTheme = false, dynamicColor = false, syncWindow = false) {
            WelcomePage(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                reduceMotion = true,
                onFilter = {},
                notifyOn = false,
                onNotify = onNotify,
                digestOn = true,
                onDigest = {},
            )
        }
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

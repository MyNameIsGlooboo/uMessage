package com.glooboo.umessage.dialogs

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import com.glooboo.umessage.R
import com.glooboo.umessage.ui.components.ChoiceDialog
import com.glooboo.umessage.ui.lock.AppLockTimeouts
import com.glooboo.umessage.ui.lock.lockSetupMessage
import com.glooboo.umessage.ui.theme.UMessageTheme
import org.fossify.commons.helpers.PROTECTION_FINGERPRINT
import org.fossify.commons.helpers.PROTECTION_PATTERN
import org.fossify.commons.helpers.PROTECTION_PIN
import org.fossify.commons.helpers.SHOW_ALL_TABS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class AppLockDialogTest {
    @get:Rule
    val compose = createComposeRule()

    private fun setUp(requiredHash: String = "", tab: Int = SHOW_ALL_TABS) = compose.setContent {
        UMessageTheme(darkTheme = false, dynamicColor = false, syncWindow = false) {
            AppLockDialog(requiredHash, tab, biometricSupported = false, autoBiometric = false) { _, _, _ -> }
        }
    }

    private fun type(digits: String) = digits.forEach { compose.onNodeWithText(it.toString()).performClick() }

    @Test
    fun `the too-short pin message stays until the user types again`() {
        setUp()
        type("12")
        compose.onNodeWithContentDescription("Confirm PIN").performClick()
        compose.mainClock.advanceTimeBy(5_000)
        compose.onNodeWithText("PIN must be at least 4 digits long").assertIsDisplayed()
        type("1")
        compose.onNodeWithText("PIN must be at least 4 digits long").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "fa-w411dp-h891dp")
    fun `the setup keypad shows Persian digits in Persian, like the unlock screen`() {
        setUp()
        "۱۲۳۴۵۶۷۸۹۰".forEach { compose.onNodeWithText(it.toString()).assertExists() }
        compose.onNodeWithText("1").assertDoesNotExist()
    }

    @Test
    fun `a wrong pin when checking says so and stays`() {
        setUp(requiredHash = AppLockLogic.protect(AppLockLogic.hashPin("2580")), tab = PROTECTION_PIN)
        type("1111")
        compose.onNodeWithContentDescription("Confirm PIN").performClick()
        compose.mainClock.advanceTimeBy(5_000)
        compose.onNodeWithText("Wrong PIN", substring = true).assertIsDisplayed()
        compose.onNodeWithText("2 more tries before you have to wait", substring = true).assertIsDisplayed()
    }

    @Test
    fun `the lock delay dialog shows the current value selected`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val labels = listOf(
            R.string.settings_app_lock_timeout_immediately,
            R.string.settings_app_lock_timeout_30s,
            R.string.settings_app_lock_timeout_1m,
            R.string.settings_app_lock_timeout_5m,
        ).map(app::getString)
        compose.setContent {
            ChoiceDialog(options = labels, selectedIndex = AppLockTimeouts.selectedIndex(30_000), onSelect = {}, onDismiss = {})
        }
        compose.onNodeWithText("After 30 seconds").assertIsSelected()
        // TalkBack: each option is a radio button that says whether it is the selected one.
        compose.onNodeWithText("After 30 seconds")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Selected"))
        compose.onNodeWithText(labels[0])
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Not selected"))
        assertEquals(1, AppLockTimeouts.selectedIndex(30_000))
        assertEquals(0, AppLockTimeouts.selectedIndex(0))
        assertEquals(3, AppLockTimeouts.selectedIndex(10 * 60_000))
    }

    @Test
    fun `the setup message never says to reinstall and is honest without a screen lock`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        for (type in listOf(PROTECTION_PIN, PROTECTION_PATTERN, PROTECTION_FINGERPRINT)) {
            for (secure in listOf(true, false)) {
                val text = app.getString(lockSetupMessage(type, secure))
                assertFalse(text, text.contains("reinstall", ignoreCase = true))
            }
        }
        assertEquals(R.string.lock_setup_done, lockSetupMessage(PROTECTION_PIN, deviceSecure = true))
        assertEquals(R.string.lock_setup_done_biometric, lockSetupMessage(PROTECTION_FINGERPRINT, deviceSecure = true))
        assertEquals(R.string.lock_setup_done_no_screen_lock, lockSetupMessage(PROTECTION_PATTERN, deviceSecure = false))
    }
}

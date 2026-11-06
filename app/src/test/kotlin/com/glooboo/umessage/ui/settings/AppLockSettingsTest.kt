package com.glooboo.umessage.ui.settings

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.ui.lock.AppLockAdvice
import com.glooboo.umessage.ui.theme.UMessageTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The app lock options in Settings: shown with the lock on, and the nudges for weak or biometric-only locks. */
@RunWith(AndroidJUnit4::class)
class AppLockSettingsTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(state: SettingsUiState, actions: SettingsActions = SettingsActions()) = compose.setContent {
        UMessageTheme(darkTheme = false, dynamicColor = false, syncWindow = false) {
            SettingsScreen(state, actions, SettingsPage.AppLock)
        }
    }

    @Test
    fun `lock options appear with the lock on`() {
        var clicks = ""
        show(
            SettingsUiState(appLock = true, appLockTimeoutLabel = "After 30 seconds", appLockBlockScreenshots = true),
            SettingsActions(
                onAppLockTimeout = { clicks += "t" },
                onChangeAppLock = { clicks += "c" },
                onLockNow = { clicks += "n" },
            ),
        )
        compose.onNodeWithText("Lock after leaving the app").performScrollTo().performClick()
        compose.onNodeWithText("After 30 seconds").assertExists()
        compose.onNodeWithText("Block screenshots").performScrollTo()
        compose.onNodeWithText("Change PIN, pattern or biometrics").performScrollTo().performClick()
        compose.onNodeWithText("Lock now").performScrollTo().performClick()
        assertEquals("tcn", clicks)
    }

    @Test
    fun `lock options are hidden with the lock off`() {
        show(SettingsUiState(appLock = false))
        compose.onNodeWithText("Lock after leaving the app").assertDoesNotExist()
        compose.onNodeWithText("Block screenshots").assertDoesNotExist()
    }

    @Test
    fun `a short pin or pattern gets a nudge to change it`() {
        var changed = false
        show(
            SettingsUiState(appLock = true, appLockAdvice = AppLockAdvice.ChangeWeak),
            SettingsActions(onChangeAppLock = { changed = true }),
        )
        compose.onNodeWithText("Your PIN or pattern is easy to guess").performScrollTo().performClick()
        assertEquals(true, changed)
    }

    @Test
    fun `a biometric-only lock gets a nudge to add a backup pin`() {
        show(SettingsUiState(appLock = true, appLockAdvice = AppLockAdvice.AddBackupPin))
        compose.onNodeWithText("Add a backup PIN").performScrollTo()
    }

    @Test
    fun `block screenshots reports its switch`() {
        var blocked: Boolean? = null
        show(SettingsUiState(appLock = true), SettingsActions(onBlockScreenshots = { blocked = it }))
        compose.onNodeWithText("Block screenshots").performScrollTo().performClick()
        assertEquals(true, blocked) // off by default, so a tap turns it on
    }
}

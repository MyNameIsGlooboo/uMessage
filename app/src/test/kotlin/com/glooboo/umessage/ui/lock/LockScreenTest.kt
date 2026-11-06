package com.glooboo.umessage.ui.lock

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.ui.theme.UMessageTheme
import org.fossify.commons.helpers.PROTECTION_FINGERPRINT
import org.fossify.commons.helpers.PROTECTION_PATTERN
import org.fossify.commons.helpers.PROTECTION_PIN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The lock screen's own UI under Robolectric: screen-reader pattern entry and the biometric fallbacks. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class LockScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(state: LockUiState, callbacks: LockCallbacks) = compose.setContent {
        UMessageTheme(darkTheme = false, dynamicColor = false, syncWindow = false) { LockContent(state, callbacks) }
    }

    private fun dot(row: Int, col: Int) = compose.onNodeWithContentDescription("Dot in row $row, column $col", substring = true)

    @Test
    fun `every pattern dot is a separate button for screen readers`() {
        show(LockUiState(PROTECTION_PATTERN), LockCallbacks())
        compose.onAllNodesWithContentDescription("Dot in row", substring = true).assertCountEquals(9)
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)
            .and(SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected))).assertCountEquals(9)
    }

    @Test
    fun `a pattern can be entered dot by dot and checked with Done`() {
        var entered: List<Int>? = null
        show(LockUiState(PROTECTION_PATTERN), LockCallbacks(onPattern = { entered = it }))
        dot(1, 1).performSemanticsAction(SemanticsActions.OnClick)
        dot(1, 2).performSemanticsAction(SemanticsActions.OnClick)
        dot(1, 3).performSemanticsAction(SemanticsActions.OnClick)
        dot(2, 3).performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithContentDescription("Dot in row 1, column 2, number 2 in the pattern").assertIsDisplayed()
        assertNull(entered)
        compose.onNodeWithText("Done").performClick()
        assertEquals(listOf(0, 1, 2, 5), entered)
    }

    @Test
    fun `clear starts the pattern over`() {
        var entered: List<Int>? = null
        show(LockUiState(PROTECTION_PATTERN), LockCallbacks(onPattern = { entered = it }))
        dot(2, 2).performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Clear").performClick()
        compose.onAllNodesWithContentDescription("in the pattern", substring = true).assertCountEquals(0)
        dot(3, 3).performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Done").performClick()
        assertEquals(listOf(8), entered)
    }

    @Test
    fun `dots do nothing during a wait`() {
        show(LockUiState(PROTECTION_PATTERN, lockoutSeconds = 30, inputEnabled = false), LockCallbacks())
        dot(1, 1).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Disabled))
        compose.onAllNodesWithContentDescription("in the pattern", substring = true).assertCountEquals(0)
    }

    @Test
    fun `a biometric lock always offers its backup pin`() {
        var asked = false
        show(LockUiState(PROTECTION_FINGERPRINT, fallback = LockFallback.Pin), LockCallbacks(onUsePin = { asked = true }))
        compose.onNodeWithText("Verify with biometrics").assertIsDisplayed()
        compose.onNodeWithText("Use PIN instead").performClick()
        assertTrue(asked)
    }

    @Test
    fun `the backup pin pad can go back to biometrics`() {
        var back = false
        show(
            LockUiState(PROTECTION_FINGERPRINT, fallback = LockFallback.Pin, usingPin = true, message = LockMessage.BiometricsUnavailable),
            LockCallbacks(onUseBiometrics = { back = true }),
        )
        compose.onNodeWithText("Biometrics are not available. Enter your PIN.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Delete").assertIsDisplayed()
        compose.onNodeWithText("Use biometrics instead").performClick()
        assertTrue(back)
    }

    @Test
    fun `an old biometric-only lock offers the phone screen lock`() {
        var asked = false
        show(LockUiState(PROTECTION_FINGERPRINT, fallback = LockFallback.DeviceCredential), LockCallbacks(onScreenLock = { asked = true }))
        compose.onNodeWithText("Use phone screen lock instead").performClick()
        assertTrue(asked)
    }

    @Test
    fun `with nothing left to check the user is let in, not shut out`() {
        var opened = false
        show(LockUiState(PROTECTION_FINGERPRINT, fallback = LockFallback.Open), LockCallbacks(onScreenLock = { opened = true }))
        compose.onNodeWithText("Open uMessage").performClick()
        assertTrue(opened)
    }

    @Test
    fun `a pin lock shows the keypad`() {
        var digits = ""
        show(LockUiState(PROTECTION_PIN), LockCallbacks(onDigit = { digits += it }))
        compose.onNodeWithText("7").performClick()
        compose.onNodeWithText("0").performClick()
        assertEquals("70", digits)
    }

    @Test
    fun `a wrong pin says so with the tries left`() {
        show(LockUiState(PROTECTION_PIN, message = LockMessage.Wrong, triesLeft = 2), LockCallbacks())
        compose.onNodeWithText("Wrong PIN, try again", substring = true).assertIsDisplayed()
        compose.onNodeWithText("2 more tries before you have to wait", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a wrong pattern says so`() {
        show(LockUiState(PROTECTION_PATTERN, message = LockMessage.Wrong, triesLeft = 1), LockCallbacks())
        compose.onNodeWithText("Wrong pattern, try again", substring = true).assertIsDisplayed()
        compose.onNodeWithText("1 more try before you have to wait", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a forgotten pin can be replaced by the phone screen lock`() {
        var asked = false
        show(LockUiState(PROTECTION_PIN, screenLockAvailable = true), LockCallbacks(onScreenLock = { asked = true }))
        compose.onNodeWithText("Forgot it? Use your phone's screen lock").performClick()
        assertTrue(asked)
    }

    @Test
    fun `the pattern lock offers forgot too`() {
        var asked = false
        show(LockUiState(PROTECTION_PATTERN, screenLockAvailable = true), LockCallbacks(onScreenLock = { asked = true }))
        compose.onNodeWithText("Forgot it? Use your phone's screen lock").performClick()
        assertTrue(asked)
    }

    @Test
    fun `without a phone screen lock there is no forgot button`() {
        show(LockUiState(PROTECTION_PIN, screenLockAvailable = false), LockCallbacks())
        compose.onNodeWithText("Forgot it?", substring = true).assertDoesNotExist()
    }

    @Test
    fun `right-to-left keeps the keypad and pattern dots left to right`() {
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                UMessageTheme(darkTheme = false, dynamicColor = false, syncWindow = false) {
                    Column {
                        LockContent(LockUiState(PROTECTION_PIN), LockCallbacks(), Modifier.weight(1f))
                        LockContent(LockUiState(PROTECTION_PATTERN), LockCallbacks(), Modifier.weight(1f))
                    }
                }
            }
        }
        fun left(text: String) = compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.left
        assertTrue(left("1") < left("2") && left("2") < left("3"))
        assertTrue(left("7") < left("9"))
        fun dotLeft(row: Int, col: Int) = dot(row, col).fetchSemanticsNode().boundsInRoot.left
        assertTrue(dotLeft(1, 1) < dotLeft(1, 2) && dotLeft(1, 2) < dotLeft(1, 3))
    }
}

package com.glooboo.umessage.ui.onboarding

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.ui.theme.UMessageTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** M-UI7: a refused default-SMS dialog leaves a way forward: Android's default apps page, as on the Setup page. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class OnboardingDeadEndsTest {
    @get:Rule
    val compose = createComposeRule()

    private fun page(denied: Boolean, unavailable: Boolean = false, isDefault: Boolean = false, onSettings: () -> Unit) =
        compose.setContent {
            UMessageTheme(darkTheme = false, dynamicColor = false, syncWindow = false) {
                val items = permissionItems()
                AllowPage(
                    modifier = Modifier.fillMaxSize(),
                    items = items,
                    granted = items.associateWith { false },
                    permanentlyDenied = emptySet(),
                    defaultSms = DefaultSmsState(isDefault, denied, unavailable, {}, onSettings),
                    onRequest = {},
                    onAllowAll = {},
                    onOpenSettings = {},
                )
            }
        }

    @Test
    fun aRefusedDialogOffersAndroidsDefaultAppsPage() {
        var opened = 0
        page(denied = true) { opened++ }
        compose.onNodeWithTag(DEFAULT_SETTINGS_TAG).performClick()
        assertEquals(1, opened)
    }

    @Test
    fun noDialogAtAllOffersItToo() {
        var opened = 0
        page(denied = false, unavailable = true) { opened++ }
        compose.onNodeWithTag(DEFAULT_SETTINGS_TAG).performClick()
        assertEquals(1, opened)
    }

    @Test
    fun beforeAnyAnswerOrOnceDefaultThereIsNoSettingsButton() {
        page(denied = false) {}
        compose.onNodeWithTag(DEFAULT_SETTINGS_TAG).assertDoesNotExist()
    }

    @Test
    fun onceDefaultTheButtonGoes() {
        page(denied = true, isDefault = true) {}
        compose.onNodeWithTag(DEFAULT_SETTINGS_TAG).assertDoesNotExist()
    }
}

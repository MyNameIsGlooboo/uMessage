package com.glooboo.umessage.ui.main

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.ui.theme.UMessageTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** A fresh install (nothing archived, no forward rules, nothing answered yet) shows the Archived tab at once. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class MainTabsFreshInstallTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the bottom bar of a fresh config lists Messages, Spam and Archive`() {
        val fresh = MainTabInputs(
            archiveAvailable = true,
            archivedCount = 0,
            forwardRuleCount = 0,
            showForwarded = null,
        )
        val tabs = MainTabPolicy.visibleTabs(fresh, MainTab.Messages)
        compose.setContent {
            UMessageTheme(darkTheme = false, dynamicColor = false, syncWindow = false) {
                MainNavigationBar(
                    tabs = tabs,
                    selected = MainTab.Messages,
                    newBlocked = 0,
                    forwardedFailed = false,
                    forwardedPaused = false,
                    archivedNew = false,
                    enabled = true,
                    onSelect = {},
                )
            }
        }
        compose.onNodeWithText("Messages").assertIsDisplayed()
        compose.onNodeWithText("Spam").assertIsDisplayed()
        compose.onNodeWithText("Archive").assertIsDisplayed()
        compose.onNodeWithText("Forwarded").assertDoesNotExist()
    }
}

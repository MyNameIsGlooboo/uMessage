package com.glooboo.umessage.ui.onboarding

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.ui.theme.UMessageTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The Allow step: one "Allow all" button and a one-line-per-item checklist. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class OnboardingAllowPageTest {
    @get:Rule
    val compose = createComposeRule()

    private val items = permissionItems()

    private fun page(
        isDefault: Boolean,
        grantedAll: Boolean,
        onAllowAll: () -> Unit = {},
        onRequest: (PermissionItem) -> Unit = {},
    ) = compose.setContent {
        UMessageTheme(darkTheme = false, dynamicColor = false, syncWindow = false) {
            AllowPage(
                modifier = Modifier.fillMaxSize(),
                items = items,
                granted = items.associateWith { grantedAll },
                permanentlyDenied = emptySet(),
                defaultSms = DefaultSmsState(isDefault, denied = false, unavailable = false, {}, {}),
                onRequest = onRequest,
                onAllowAll = onAllowAll,
                onOpenSettings = {},
            )
        }
    }

    @Test
    fun `Allow all shows while something is missing and reports a tap`() {
        var taps = 0
        page(isDefault = false, grantedAll = false, onAllowAll = { taps++ })
        compose.onNodeWithTag(ALLOW_ALL_TAG).assertIsDisplayed().performClick()
        compose.onNodeWithText("Allow all").assertIsDisplayed()
        assertEquals(1, taps)
        compose.onNodeWithText("All set").assertDoesNotExist()
    }

    @Test
    fun `the checklist has one row per item with a short Needed status and no description text`() {
        page(isDefault = false, grantedAll = false)
        listOf("Default SMS app", "Contacts", "Messages", "Phone state").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
        // The default app row plus one per permission item, all still needed.
        assertEquals(items.size + 1, compose.onAllNodesWithText("Needed").fetchSemanticsNodes().size)
        compose.onNodeWithText("Lets contacts through the filter and shows their names.").assertDoesNotExist()
        compose.onNodeWithText("Set as default").assertDoesNotExist()
    }

    @Test
    fun `a needed row can be tapped to ask for just that item`() {
        var asked: PermissionItem? = null
        page(isDefault = true, grantedAll = false, onRequest = { asked = it })
        compose.onNodeWithText("Contacts").performClick()
        assertEquals(items.first(), asked)
    }

    @Test
    fun `when everything is granted the button gives way to All set and every row says Done`() {
        page(isDefault = true, grantedAll = true)
        compose.onNodeWithTag(ALLOW_ALL_TAG).assertDoesNotExist()
        compose.onNodeWithText("All set").assertIsDisplayed()
        assertEquals(items.size + 1, compose.onAllNodesWithText("Done").fetchSemanticsNodes().size)
    }
}

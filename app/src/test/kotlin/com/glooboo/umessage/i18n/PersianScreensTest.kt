package com.glooboo.umessage.i18n

import android.view.View
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.R
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.ui.lock.LockCallbacks
import com.glooboo.umessage.ui.lock.LockContent
import com.glooboo.umessage.ui.lock.LockUiState
import com.glooboo.umessage.ui.spam.AddRuleSheet
import com.glooboo.umessage.ui.spam.BlockedActions
import com.glooboo.umessage.ui.spam.BlockedScreen
import com.glooboo.umessage.ui.spam.BlockedUiState
import com.glooboo.umessage.ui.spam.RuleForm
import com.glooboo.umessage.ui.spam.RulesActions
import com.glooboo.umessage.ui.theme.UMessageTheme
import org.fossify.commons.helpers.PROTECTION_PIN
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Smoke test: with the phone in Persian, key screens show Persian text and lay out right to left. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "fa-w411dp-h891dp")
class PersianScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private var direction: LayoutDirection? = null

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) = compose.setContent {
        direction = LocalLayoutDirection.current
        UMessageTheme(darkTheme = false, dynamicColor = false, syncWindow = false) { content() }
    }

    /** The Persian resource itself, so a re-translation does not break the test; the screen must show it. */
    private fun string(id: Int) = ApplicationProvider.getApplicationContext<android.content.Context>().getString(id)

    @Test
    fun `the configuration is Persian and right to left`() {
        val config = ApplicationProvider.getApplicationContext<android.content.Context>().resources.configuration
        assertEquals("fa", config.locales[0].language)
        assertEquals(View.LAYOUT_DIRECTION_RTL, config.layoutDirection)
    }

    @Test
    fun `the blocked screen is in Persian`() {
        show { BlockedScreen(BlockedUiState(loading = false), SnackbarHostState(), BlockedActions(onBack = {})) }
        compose.onNodeWithText("مسدودشده‌ها").assertExists()
        compose.onNodeWithText(string(R.string.spam_blocked_empty_title)).assertExists()
        assertEquals(LayoutDirection.Rtl, direction)
    }

    @Test
    fun `the lock screen is in Persian`() {
        show { LockContent(LockUiState(PROTECTION_PIN), LockCallbacks()) }
        compose.onNodeWithText("uMessage قفل است").assertExists()
        assertEquals(LayoutDirection.Rtl, direction)
    }

    @Test
    fun `the add rule sheet is in Persian`() {
        show { AddRuleSheet(RuleForm(kind = ListKind.BLOCK, type = RuleType.KEYWORD), emptyList(), RulesActions()) }
        compose.onNodeWithText(string(R.string.ux8d_match_label)).assertExists()
        assertEquals(LayoutDirection.Rtl, direction)
    }
}

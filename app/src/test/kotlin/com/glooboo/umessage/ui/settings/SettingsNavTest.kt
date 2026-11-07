package com.glooboo.umessage.ui.settings

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.isToggleable
import androidx.test.core.app.ApplicationProvider
import com.glooboo.umessage.R
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.glooboo.umessage.spam.UnknownSenderMode
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.ui.theme.UMessageTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The short main list (P4): SIM and forwarding rows depend on the phone, the rest opens sub-pages. */
@RunWith(AndroidJUnit4::class)
class SettingsNavTest {
    @get:Rule
    val compose = createComposeRule()

    private fun str(@StringRes id: Int): String =
        ApplicationProvider.getApplicationContext<Context>().getString(id)

    private val twoSims = listOf(
        SimCard(1, 1, "Jio", null, isDefaultSms = true),
        SimCard(2, 2, "Airtel", null, isDefaultSms = false),
    )

    private fun show(
        state: SettingsUiState,
        actions: SettingsActions = SettingsActions(),
        page: SettingsPage = SettingsPage.Main,
    ) = compose.setContent {
        UMessageTheme(darkTheme = false, dynamicColor = false, syncWindow = false) {
            SettingsScreen(state, actions, page)
        }
    }

    @Test
    fun `a single sim shows no sim rows`() {
        show(SettingsUiState(sims = twoSims.take(1)))
        compose.onNodeWithText("Default SIM for new messages").assertDoesNotExist()
        compose.onNodeWithText("Number not available").assertDoesNotExist()
        compose.onNodeWithText("Rules per SIM").assertDoesNotExist()
    }

    @Test
    fun `two sims show one default sim row`() {
        var chosen = false
        show(SettingsUiState(sims = twoSims), SettingsActions(onChooseDefaultSim = { chosen = true }))
        compose.onNodeWithText("Default SIM for new messages").performScrollTo().performClick()
        assertTrue(chosen)
        compose.onNodeWithText("Number not available").assertDoesNotExist()
    }

    /** Round 3: the Forwarding row is always there and opens the forwarding screen; its summary says the state. */
    @Test
    fun `forwarding row is always shown and opens forwarding`() {
        var opened = false
        show(SettingsUiState(forwardRuleCount = 0), SettingsActions(onOpenForwardRules = { opened = true }))
        compose.onNodeWithText("Off · Forward messages to another number").assertExists()
        compose.onNodeWithText("Forwarding").performScrollTo().performClick()
        assertTrue(opened)
    }

    @Test
    fun `forwarding summary follows the rules and the pause`() {
        assertEquals(ForwardingSummary.NO_RULES, SettingsUiState().forwardingSummary)
        assertEquals(ForwardingSummary.OFF, SettingsUiState(forwardRuleCount = 2).forwardingSummary)
        assertEquals(ForwardingSummary.ON, SettingsUiState(forwardRuleCount = 2, forwardRulesOn = 1).forwardingSummary)
        assertEquals(
            ForwardingSummary.PAUSED,
            SettingsUiState(forwardRuleCount = 2, forwardRulesOn = 2, pauseAllForwarding = true).forwardingSummary,
        )
        show(SettingsUiState(forwardRuleCount = 3, forwardRulesOn = 2))
        compose.onNodeWithText("2 rules on").assertExists()
    }

    @Test
    fun `forwarding page reports its switches`() {
        var tab: Boolean? = null
        var paused: Boolean? = null
        show(
            SettingsUiState(forwardRuleCount = 1),
            SettingsActions(onShowForwardedTab = { tab = it }, onPauseForwarding = { paused = it }),
            SettingsPage.Forwarding,
        )
        // Owner notes R2: titled apart from the Forwarding screen that opens it.
        compose.onNodeWithText("Forwarding options").assertExists()
        compose.onNodeWithText("Show Forwarded tab").performClick()
        compose.onNodeWithText("Pause all forwarding").performClick()
        assertEquals(true, tab)
        assertEquals(true, paused)
    }

    @Test
    fun `main rows open their pages`() {
        show(SettingsUiState(forwardRuleCount = 2))
        compose.onNodeWithText(str(R.string.ux8e_nav_typing)).performScrollTo().performClick()
        compose.onNodeWithText(str(R.string.settings_nav_group_sending)).assertExists()
    }

    // U05: the main list is a list of pages; every switch sits on the page of its topic.
    @Test
    fun `main list holds no switch`() {
        show(SettingsUiState(forwardRuleCount = 2, spam = spam))
        compose.onAllNodes(isToggleable()).assertCountEquals(0)
    }

    // U44: with the lock off the row starts the set-up; with it on the row opens the options page.
    @Test
    fun `app lock row starts setup when off and opens options when on`() {
        var started = false
        show(SettingsUiState(appLock = false), SettingsActions(onAppLock = { started = true }))
        compose.onNodeWithText(str(R.string.settings_nav_app_lock)).performScrollTo().performClick()
        assertTrue(started)
    }

    @Test
    fun `app lock row opens the options page when on`() {
        show(SettingsUiState(appLock = true, appLockTimeoutLabel = "After 30 seconds"))
        compose.onNodeWithText(str(R.string.settings_nav_app_lock)).performScrollTo().performClick()
        compose.onNodeWithText(str(R.string.settings_app_lock_timeout)).assertExists()
    }

    // U07, U26: the bin switch is the first row of the storage page; rows that mention the bin show only while it
    // is on.
    @Test
    fun `storage page shows the bin rows only while the bin is on`() {
        var bin: Boolean? = null
        show(SettingsUiState(useRecycleBin = false), SettingsActions(onRecycleBin = { bin = it }), SettingsPage.Storage)
        compose.onNodeWithText(str(R.string.cleanup_codes_title)).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.cleanup_old_title)).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.ux8e_recycle_bin)).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.ux8e_keep_deleted)).performClick()
        assertEquals(true, bin)
    }

    @Test
    fun `storage page with the bin on shows the bin and the cleanup`() {
        show(SettingsUiState(useRecycleBin = true, recycleBinLabel = "4 messages"), page = SettingsPage.Storage)
        compose.onNodeWithText(str(R.string.ux8e_recycle_bin)).assertExists()
        compose.onNodeWithText(str(R.string.cleanup_codes_title)).performScrollTo().assertExists()
        compose.onNodeWithText(str(R.string.cleanup_old_title)).performScrollTo().assertExists()
    }

    // U22: the blocked alert, the lock screen rows and the weekly summary are one page.
    @Test
    fun `notifications page holds the alert rows`() {
        show(SettingsUiState(spam = spam), page = SettingsPage.Notifications)
        compose.onNodeWithText(str(R.string.spam_notify_title)).assertExists()
        compose.onNodeWithText(str(R.string.settings_nav_lock_screen)).performScrollTo().assertExists()
        compose.onNodeWithText(str(R.string.w3d_hide_lock_codes)).performScrollTo().assertExists()
        compose.onNodeWithText(str(R.string.digest_row_title)).performScrollTo().assertExists()
    }

    private val spam = SpamSummary(UnknownSenderMode.SMART, blockedLast7Days = 1, blockedTotal = 1, restoredTotal = 0)

    // Owner notes r3: the spam row's parts are joined with the separator, never a comma.
    @Test
    fun `spam summary joins its parts with the separator`() {
        show(SettingsUiState(spam = SpamSummary(UnknownSenderMode.SMART, 17, 17, 11)))
        compose.onNodeWithText("Smart · 17 stopped · 11 restored").assertExists()
        val resources = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.content.Context>().resources
        assertEquals(
            "Smart · 2 stopped in 7 days · 30 in total",
            spamSummaryLine(resources, SpamSummary(UnknownSenderMode.SMART, 2, 30, 0), "Smart"),
        )
    }

    // P4-11: the mode is chosen on the Spam protection page; Strict asks first (SE-23).
    @Test
    fun `spam page chooses the mode and confirms strict`() {
        val chosen = mutableListOf<UnknownSenderMode>()
        show(SettingsUiState(spam = spam), SettingsActions(onSpamMode = { chosen += it }), SettingsPage.Spam)
        compose.onNodeWithText("Off").performClick()
        compose.onNodeWithText("Strict").performClick()
        assertEquals(listOf(UnknownSenderMode.OFF), chosen)
        compose.onNodeWithText("Use Strict").performClick()
        assertEquals(listOf(UnknownSenderMode.OFF, UnknownSenderMode.STRICT), chosen)
    }

    // B7: "Recommended" only once, as the chip.
    @Test
    fun `smart mode says recommended once`() {
        show(SettingsUiState(spam = spam), page = SettingsPage.Spam)
        compose.onAllNodesWithText("Recommended", substring = true).assertCountEquals(1)
    }

    // SE-16, U09: one Backup page with two verbs; each asks what: messages, or rules and settings.
    @Test
    fun `backup page has back up and restore that each ask what`() {
        val calls = mutableListOf<String>()
        show(
            SettingsUiState(),
            SettingsActions(
                onBackupSettings = { calls += "backup-settings" },
                onRestoreSettings = { calls += "restore-settings" },
                onExport = { calls += "export" },
                onImport = { calls += "import" },
            ),
        )
        compose.onNodeWithText(str(R.string.settings_group_backup)).performScrollTo().performClick()
        compose.onNodeWithText(str(R.string.ux8e_backup_row)).performScrollTo().performClick()
        compose.onNodeWithText(str(R.string.ux8e_what_messages)).performClick()
        compose.onNodeWithText(str(R.string.ux8e_restore_row)).performClick()
        compose.onNodeWithText(str(R.string.ux8e_what_settings)).performClick()
        assertEquals(listOf("export", "restore-settings"), calls)
    }

    // SE-09: the blocked-message alert sits on the Notifications page.
    @Test
    fun `blocked alert is a notification setting`() {
        var notify: Boolean? = null
        show(
            SettingsUiState(spam = spam),
            SettingsActions(onNotifyOnBlock = { notify = it }),
            SettingsPage.Notifications,
        )
        compose.onNodeWithText("Notify when a message is blocked").performScrollTo().performClick()
        assertEquals(true, notify)
    }

    @Test
    fun `language row shows the current choice`() {
        var opened = false
        show(
            SettingsUiState(showLanguage = true, languageName = "فارسی"),
            SettingsActions(onLanguage = { opened = true }),
        )
        compose.onNodeWithText("فارسی").performScrollTo().performClick()
        assertTrue(opened)
    }
}

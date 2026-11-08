package com.glooboo.umessage.ui.settings

import com.glooboo.umessage.helpers.DEFAULT_SIM_ASK
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.spam.UnknownSenderMode
import com.glooboo.umessage.ui.components.SettingsGroup
import com.glooboo.umessage.ui.components.SettingsRadioRow
import com.glooboo.umessage.ui.components.SettingsRow
import com.glooboo.umessage.ui.components.SettingsSwitchRow
import com.glooboo.umessage.ui.components.ConfirmDialog
import com.glooboo.umessage.ui.components.UMessageScaffold
import com.glooboo.umessage.ui.components.UMessageTopBar
import com.glooboo.umessage.ui.components.TonalRows
import androidx.compose.foundation.layout.PaddingValues
import com.glooboo.umessage.ui.icons.ShortcutIcons
import com.glooboo.umessage.ui.icons.Symbols
import com.glooboo.umessage.ui.lock.AppLockAdvice
import com.glooboo.umessage.ui.settings.search.SettingsSearch
import com.glooboo.umessage.ui.settings.search.SettingsSearchButton
import com.glooboo.umessage.ui.settings.search.SettingsSearchField
import com.glooboo.umessage.ui.settings.search.SettingsSearchResults
import androidx.compose.material3.Icon
import com.glooboo.umessage.ui.theme.UMessageTheme
import org.fossify.commons.R as CommonsR

/**
 * Settings: a short main list (P4) whose rows open sub-pages. The pages live inside this screen, so the activity
 * stays one screen and back steps from a page to the list first. [initialPage] is for previews and tests.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(state: SettingsUiState, actions: SettingsActions, initialPage: SettingsPage = SettingsPage.Main) {
    var page by rememberSaveable { mutableStateOf(initialPage) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val goBack: () -> Unit = {
        // A page another screen opened directly (the forward editor's App lock) goes back to that screen.
        if (searching) {
            searching = false
            query = ""
        } else if (page == SettingsPage.Main || page == initialPage) {
            actions.onBack()
        } else if (page == SettingsPage.Licenses) {
            page = SettingsPage.About
        } else if (page == SettingsPage.MoveFromApp) {
            page = SettingsPage.Backup
        } else {
            page = SettingsPage.Main
        }
    }
    BackHandler(enabled = page != SettingsPage.Main || searching) { goBack() }
    val title = stringResource(pageTitle(page))
    val topBar: @Composable () -> Unit = {
        if (searching) {
            SettingsSearchField(query, onQueryChange = { query = it }, onClose = goBack)
        } else {
            UMessageTopBar(
                title = title,
                onBack = goBack,
                actions = { if (page == SettingsPage.Main) SettingsSearchButton(onClick = { searching = true }) },
            )
        }
    }
    SettingsFrame(topBar) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val open: (SettingsPage) -> Unit = { page = it }
            if (searching) {
                SettingsSearchResults(
                    query = query,
                    available = SettingsSearch.available(state.isDualSim, state.showDecisionLog, state.showLanguage),
                    forwarding = state.forwardingAvailable,
                    onOpen = { entry ->
                        if (entry.target.inScreen) {
                            searching = false
                            query = ""
                        }
                        entry.target.open(open, actions)
                    },
                )
            } else {
                PageContent(page, state, actions, open)
            }
            Spacer(Modifier.height(24.dp + padding.calculateBottomPadding()))
        }
    }
}

@Composable
private fun PageContent(
    page: SettingsPage,
    state: SettingsUiState,
    actions: SettingsActions,
    open: (SettingsPage) -> Unit,
) {
    when (page) {
        SettingsPage.Main -> MainPage(state, actions, open)
        SettingsPage.Spam -> SpamPage(state, actions)
        SettingsPage.Forwarding ->
            if (state.forwardingAvailable) ForwardingPage(state, actions) else MainPage(state, actions, open)
        SettingsPage.Notifications -> NotificationsPage(state, actions)
        SettingsPage.Typing -> TypingPage(state, actions)
        SettingsPage.Storage -> StoragePage(state, actions)
        SettingsPage.Backup -> BackupPage(actions, open)
        SettingsPage.MoveFromApp -> MoveFromAppPage(actions.onImport)
        SettingsPage.AppLock -> AppLockPage(state, actions)
        SettingsPage.About -> AboutPage(state, actions, open)
        SettingsPage.Licenses -> LicensesPage(actions)
    }
}

/** U62: every page, the main list too, uses the same small top bar, so the first rows start right under the title. */
@Composable
private fun SettingsFrame(topBar: @Composable () -> Unit, content: @Composable (PaddingValues) -> Unit) {
    UMessageScaffold(topBar = topBar, content = content)
}

private fun pageTitle(page: SettingsPage): Int = when (page) {
    SettingsPage.Main -> R.string.settings_title
    SettingsPage.Spam -> R.string.settings_nav_spam_page_title
    // Owner notes R2: the options page opened from the Forwarding screen's tune button is not named like that screen.
    SettingsPage.Forwarding -> R.string.r3fwd_options
    SettingsPage.Notifications -> R.string.settings_group_notifications
    SettingsPage.Typing -> R.string.ux8e_nav_typing
    SettingsPage.Storage -> R.string.ux8e_nav_storage
    SettingsPage.Backup -> R.string.settings_group_backup
    SettingsPage.MoveFromApp -> R.string.move_from_app_title
    SettingsPage.AppLock -> R.string.settings_nav_app_lock
    SettingsPage.About -> R.string.settings_nav_about_row
    SettingsPage.Licenses -> R.string.settings_nav_licenses_title
}

// region Main page

@Composable
private fun MainPage(state: SettingsUiState, actions: SettingsActions, open: (SettingsPage) -> Unit) {
    SettingsGroup(stringResource(R.string.settings_nav_group_protection)) {
        SettingsRow(
            title = stringResource(R.string.settings_nav_spam_row),
            summary = spamSummaryText(state.spam),
            icon = Symbols.Shield,
            onClick = { open(SettingsPage.Spam) },
            showChevron = true,
        )
        // Round 3: always here, and it opens the forward rules themselves; their switches are one tap further.
        // Not in a build without forwarding.
        if (state.forwardingAvailable) {
            SettingsRow(
                title = stringResource(R.string.r3fwd_title),
                summary = forwardingSummaryText(state),
                icon = Symbols.Forward,
                onClick = actions.onOpenForwardRules,
                showChevron = true,
            )
        }
    }
    // U05: no switch on this list; every one sits on the page of its topic.
    SettingsGroup(stringResource(R.string.settings_nav_group_messages)) {
        SettingsRow(
            title = stringResource(R.string.settings_group_notifications),
            summary = stringResource(R.string.ux8e_nav_notifications_body),
            icon = Symbols.Notifications,
            onClick = { open(SettingsPage.Notifications) },
            showChevron = true,
        )
        SettingsRow(
            title = stringResource(R.string.ux8e_nav_typing),
            summary = stringResource(R.string.ux8e_nav_typing_body),
            icon = Symbols.Send,
            onClick = { open(SettingsPage.Typing) },
            showChevron = true,
        )
        SettingsRow(
            title = stringResource(R.string.ux8e_nav_storage),
            summary = stringResource(R.string.ux8e_nav_storage_body),
            icon = Symbols.DeleteSweep,
            onClick = { open(SettingsPage.Storage) },
            showChevron = true,
        )
        // P4-02: a single SIM, or no phone-state permission, means no SIM vocabulary at all.
        if (state.isDualSim) DefaultSimRow(state, actions)
    }
    // SE-46: the row is named like the page it opens; the group is not named like the row.
    SettingsGroup(stringResource(R.string.settings_f_group_display)) {
        SettingsRow(
            title = stringResource(R.string.settings_f_appearance),
            summary = stringResource(R.string.settings_nav_theme_body),
            icon = Symbols.Palette,
            onClick = actions.onTheme,
            showChevron = true,
        )
        LanguageRow(state, actions)
    }
    SettingsGroup(stringResource(R.string.settings_nav_group_privacy_about)) {
        // SE-16: one Backup page for messages, rules and settings.
        SettingsRow(
            title = stringResource(R.string.settings_group_backup),
            summary = stringResource(R.string.settings_f_backup_row_body),
            icon = Symbols.Upload,
            onClick = { open(SettingsPage.Backup) },
            showChevron = true,
        )
        AppLockEntryRow(state, actions, open)
        SettingsRow(
            title = stringResource(R.string.settings_nav_about_row),
            summary = stringResource(R.string.settings_nav_about_body),
            icon = Symbols.Info,
            onClick = { open(SettingsPage.About) },
            showChevron = true,
        )
    }
}

@Composable
private fun LanguageRow(state: SettingsUiState, actions: SettingsActions) {
    if (state.showLanguage) {
        SettingsRow(
            title = stringResource(CommonsR.string.language),
            value = state.languageName,
            icon = Symbols.Language,
            onClick = actions.onLanguage,
            showChevron = true,
        )
    } else {
        SettingsRow(
            title = stringResource(CommonsR.string.language),
            summary = stringResource(R.string.settings_language_phone_note),
            icon = Symbols.Language,
        )
    }
}

/** U44: with the lock off the row starts the set-up itself; with it on, the row opens the page of options. */
@Composable
private fun AppLockEntryRow(state: SettingsUiState, actions: SettingsActions, open: (SettingsPage) -> Unit) {
    SettingsRow(
        title = stringResource(R.string.settings_nav_app_lock),
        summary = if (state.appLock) {
            stringResource(R.string.settings_nav_on_with, state.appLockTimeoutLabel)
        } else {
            stringResource(R.string.settings_nav_off)
        },
        icon = Symbols.Fingerprint,
        onClick = if (state.appLock) {
            { open(SettingsPage.AppLock) }
        } else {
            actions.onAppLock
        },
        showChevron = true,
    )
}

/** The Forwarding row's summary: "Off", "Paused" or "2 rules on" (round 3). */
@Composable
private fun forwardingSummaryText(state: SettingsUiState): String = when (state.forwardingSummary) {
    ForwardingSummary.NO_RULES -> stringResource(R.string.r3fwd_summary_none)
    ForwardingSummary.OFF -> stringResource(R.string.r3fwd_summary_off)
    ForwardingSummary.PAUSED -> stringResource(R.string.r3fwd_summary_paused)
    ForwardingSummary.ON -> pluralStringResource(R.plurals.r3fwd_summary_on, state.forwardRulesOn, state.forwardRulesOn)
}

/**
 * One line: the mode and the figures. When the 7-day and total counts are equal only one is shown (SE-02). The parts
 * are joined with the app's separator (" · ", or the Persian comma), like the other summaries (owner notes r3).
 */
@Composable
private fun spamSummaryText(spam: SpamSummary?): String {
    if (spam == null) return stringResource(R.string.settings_spam_open_settings_body)
    return spamSummaryLine(LocalResources.current, spam, modeLabel(spam.mode))
}

/** The Spam protection row's summary parts, joined with [R.string.ui_separator]. */
internal fun spamSummaryLine(resources: android.content.res.Resources, spam: SpamSummary, mode: String): String {
    val figures = if (spam.blockedLast7Days == spam.blockedTotal) {
        listOf(
            resources.getString(R.string.r3f_spam_summary_stopped, spam.blockedTotal),
            resources.getString(R.string.r3f_spam_summary_restored, spam.restoredTotal),
        )
    } else {
        listOf(
            resources.getString(R.string.r3f_spam_summary_stopped_week, spam.blockedLast7Days),
            resources.getString(R.string.r3f_spam_summary_total, spam.blockedTotal),
        )
    }
    return (listOf(mode) + figures).joinToString(resources.getString(R.string.ui_separator))
}

@Composable
private fun modeLabel(mode: UnknownSenderMode): String = stringResource(
    when (mode) {
        UnknownSenderMode.STRICT -> R.string.spam_mode_strict
        UnknownSenderMode.SMART -> R.string.spam_mode_smart
        UnknownSenderMode.OFF -> R.string.spam_mode_off
    },
)

/** The single SIM row of a dual-SIM phone: the SIM new conversations start on, with a short value. */
@Composable
private fun DefaultSimRow(state: SettingsUiState, actions: SettingsActions) {
    @Composable fun describe(sim: SimCard) = stringResource(
        R.string.settings_nav_default_sim_value,
        sim.slot,
        sim.carrier.ifBlank { stringResource(R.string.settings_sim_slot_unnamed, sim.slot) },
    )
    val phoneDefault = state.sims.firstOrNull { it.isDefaultSms }
    val chosen = state.sims.firstOrNull { it.subscriptionId == state.defaultSimId }
    val current = when {
        state.defaultSimId == DEFAULT_SIM_ASK -> stringResource(R.string.settings_nav_default_sim_ask)
        chosen != null -> describe(chosen)
        phoneDefault != null -> stringResource(R.string.settings_sim_default_phone_value, describe(phoneDefault))
        else -> stringResource(R.string.settings_sim_default_phone)
    }
    SettingsRow(
        title = stringResource(R.string.settings_sim_default_title),
        summary = current,
        icon = Symbols.SimCard,
        onClick = actions.onChooseDefaultSim,
        showChevron = true,
    )
}

// endregion

// region Sub-pages

@Composable
private fun SpamPage(state: SettingsUiState, actions: SettingsActions) {
    // P4-11: the mode is chosen right here, not two pages deeper.
    val spam = state.spam
    if (spam != null) SpamModeGroup(spam, actions)
    // B11: the group is not named like the page above it.
    SettingsGroup(stringResource(R.string.settings_f_group_rules)) {
        SettingsRow(
            title = stringResource(R.string.settings_spam_open_rules),
            summary = stringResource(R.string.settings_spam_open_rules_body),
            icon = Symbols.Rule,
            onClick = actions.onOpenRules,
            showChevron = true,
        )
        SettingsRow(
            title = stringResource(R.string.rules2_bn_title),
            summary = stringResource(R.string.settings_nav_android_block_list_body),
            icon = Symbols.Block,
            onClick = actions.onOpenBlockedNumbers,
            showChevron = true,
        )
        // SU-33: the old blocked-words list is shown only until its words are moved into rules.
        if (state.showOldBlockedWords) {
            SettingsRow(
                title = stringResource(R.string.settings_old_blocked_words),
                summary = stringResource(R.string.settings_old_blocked_words_body),
                icon = Symbols.Description,
                onClick = actions.onOpenBlockedKeywords,
                showChevron = true,
            )
        }
        if (state.showDecisionLog) {
            SettingsRow(
                title = stringResource(R.string.settings_nav_decision_log),
                summary = stringResource(R.string.settings_nav_decision_log_body),
                icon = Symbols.Visibility,
                onClick = actions.onOpenDecisionLog,
                showChevron = true,
            )
        }
        SettingsRow(
            title = stringResource(
                if (spam != null) R.string.settings_f_spam_more else R.string.settings_spam_open_settings,
            ),
            summary = stringResource(
                if (spam != null) R.string.settings_f_spam_more_body else R.string.settings_spam_open_settings_body,
            ),
            icon = Symbols.Tune,
            onClick = actions.onOpenSpamSettings,
            showChevron = true,
        )
    }
}

/** The three unknown-sender modes. Strict also blocks verification codes, so it asks first (SE-23). */
@Composable
private fun SpamModeGroup(spam: SpamSummary, actions: SettingsActions) {
    var confirmStrict by rememberSaveable { mutableStateOf(false) }
    val choose: (UnknownSenderMode) -> Unit = { mode ->
        if (mode == UnknownSenderMode.STRICT && spam.mode != UnknownSenderMode.STRICT) {
            confirmStrict = true
        } else if (mode != spam.mode) {
            actions.onSpamMode(mode)
        }
    }
    SettingsGroup(
        title = stringResource(R.string.spam_settings_unknown_title),
        description = spamSummaryText(spam),
    ) {
        SettingsRadioRow(
            title = stringResource(R.string.spam_mode_strict),
            summary = stringResource(R.string.spam_mode_strict_body),
            selected = spam.mode == UnknownSenderMode.STRICT,
            onSelect = { choose(UnknownSenderMode.STRICT) },
        )
        SettingsRadioRow(
            title = stringResource(R.string.spam_mode_smart),
            summary = stringResource(R.string.spam_mode_smart_body),
            selected = spam.mode == UnknownSenderMode.SMART,
            onSelect = { choose(UnknownSenderMode.SMART) },
            badge = stringResource(R.string.settings_nav_recommended),
        )
        SettingsRadioRow(
            title = stringResource(R.string.spam_mode_off),
            summary = stringResource(R.string.spam_mode_off_body),
            selected = spam.mode == UnknownSenderMode.OFF,
            onSelect = { choose(UnknownSenderMode.OFF) },
        )
    }
    if (confirmStrict) {
        ConfirmDialog(
            title = stringResource(R.string.settings_nav_strict_confirm_title),
            message = stringResource(R.string.settings_nav_strict_confirm_body),
            confirmLabel = stringResource(R.string.settings_nav_strict_confirm_action),
            dismissLabel = stringResource(R.string.settings_nav_strict_confirm_cancel),
            onConfirm = {
                confirmStrict = false
                actions.onSpamMode(UnknownSenderMode.STRICT)
            },
            onDismiss = { confirmStrict = false },
        )
    }
}

@Composable
private fun ForwardingPage(state: SettingsUiState, actions: SettingsActions) {
    // B11: no group title repeating the page title "Forwarding".
    TonalRows(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        SettingsSwitchRow(
            title = stringResource(R.string.settings_nav_forwarded_tab),
            summary = stringResource(R.string.settings_nav_forwarded_tab_body),
            icon = Symbols.Forward,
            checked = state.showForwardedTab,
            onCheckedChange = actions.onShowForwardedTab,
        )
        SettingsSwitchRow(
            title = stringResource(R.string.settings_nav_forward_pause),
            summary = stringResource(R.string.settings_nav_forward_pause_body),
            icon = Symbols.Pause,
            checked = state.pauseAllForwarding,
            onCheckedChange = actions.onPauseForwarding,
        )
        SettingsSwitchRow(
            title = stringResource(R.string.w3d_mask_codes),
            summary = stringResource(R.string.w3d_mask_codes_body),
            icon = Symbols.Shield,
            checked = state.maskForwardedCodes,
            onCheckedChange = actions.onMaskForwardedCodes,
        )
        if (state.maskForwardedCodes) {
            SettingsSwitchRow(
                title = stringResource(R.string.w3d_mask_keep_last),
                summary = stringResource(
                    if (state.maskKeepLastDigits) R.string.w3d_mask_keep_last_on else R.string.w3d_mask_keep_last_off,
                ),
                icon = Symbols.Edit,
                checked = state.maskKeepLastDigits,
                onCheckedChange = actions.onMaskKeepLastDigits,
            )
        }
        SettingsRow(
            title = stringResource(R.string.w3d_daily_limit),
            summary = stringResource(R.string.w3d_daily_limit_body),
            value = state.dailyForwardLimitLabel,
            icon = Symbols.Schedule,
            onClick = actions.onDailyForwardLimit,
            showChevron = true,
        )
        // Round 3: no "Forwarding rules" row. This page opens from the Forwarding screen, and Back returns there.
    }
}

@Composable
private fun AppLockPage(state: SettingsUiState, actions: SettingsActions) {
    // B11: no group title repeating the page title and the switch below it.
    TonalRows(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        SettingsSwitchRow(
            title = stringResource(R.string.settings_nav_app_lock),
            summary = stringResource(R.string.settings_nav_app_lock_body),
            icon = Symbols.Fingerprint,
            checked = state.appLock,
            onCheckedChange = { actions.onAppLock() },
        )
        if (state.appLock) AppLockRows(state, actions)
    }
}

/** Options of the app lock, shown while it is on. */
@Composable
private fun AppLockRows(state: SettingsUiState, actions: SettingsActions) {
    when (state.appLockAdvice) {
        AppLockAdvice.None -> Unit
        AppLockAdvice.ChangeWeak -> SettingsRow(
            title = stringResource(R.string.settings_app_lock_weak),
            summary = stringResource(R.string.settings_app_lock_weak_body),
            icon = Symbols.Warning,
            onClick = actions.onChangeAppLock,
            showChevron = true,
        )
        AppLockAdvice.AddBackupPin -> SettingsRow(
            title = stringResource(R.string.settings_app_lock_add_pin),
            summary = stringResource(R.string.settings_app_lock_add_pin_body),
            icon = Symbols.Warning,
            onClick = actions.onChangeAppLock,
            showChevron = true,
        )
    }
    SettingsRow(
        title = stringResource(R.string.settings_app_lock_timeout),
        value = state.appLockTimeoutLabel,
        icon = Symbols.Schedule,
        onClick = actions.onAppLockTimeout,
        showChevron = true,
    )
    SettingsRow(
        title = stringResource(R.string.lock_scope_title),
        value = state.appLockScopeLabel,
        icon = Symbols.SelectAll,
        onClick = actions.onAppLockScope,
        showChevron = true,
    )
    SettingsSwitchRow(
        title = stringResource(R.string.settings_app_lock_screenshots),
        // Recents is hidden on every Android version now (older ones blank it while the app is left), so one text.
        summary = stringResource(R.string.settings_app_lock_screenshots_body),
        icon = Symbols.Shield,
        checked = state.appLockBlockScreenshots,
        onCheckedChange = actions.onBlockScreenshots,
    )
    if (state.appLockAdvice == AppLockAdvice.None) {
        SettingsRow(
            title = stringResource(R.string.settings_app_lock_change),
            icon = Symbols.Edit,
            onClick = actions.onChangeAppLock,
            showChevron = true,
        )
    }
    SettingsRow(
        title = stringResource(R.string.settings_app_lock_now),
        summary = stringResource(R.string.settings_app_lock_now_body),
        icon = Symbols.Lock,
        onClick = actions.onLockNow,
    )
}

@Composable
private fun AboutPage(state: SettingsUiState, actions: SettingsActions, open: (SettingsPage) -> Unit) {
    // B11: no group title repeating the "About" page title.
    TonalRows(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        SettingsRow(
            title = stringResource(R.string.settings_privacy_title),
            summary = stringResource(R.string.settings_nav_privacy_body),
            icon = Symbols.WifiOff,
        )
        SettingsRow(
            title = stringResource(R.string.privacy_policy),
            summary = stringResource(R.string.privacy_policy_body),
            icon = Symbols.Shield,
            onClick = { actions.onOpenLink(PRIVACY_POLICY_URL) },
            showChevron = true,
        )
        SettingsRow(
            title = stringResource(R.string.settings_about_version),
            // The full name (0.2.0-daily.4074+d06684d2) names the exact build a tester reports from.
            summary = state.fullVersionName.ifEmpty { state.versionName },
            icon = Symbols.Info,
            onClick = actions.onCopyVersion,
        )
        SettingsRow(
            title = stringResource(R.string.settings_about_source),
            summary = stringResource(R.string.settings_nav_source_body),
            icon = Symbols.Code,
            onClick = actions.onOpenRepo,
            showChevron = true,
        )
        if (state.showRateRow) {
            SettingsRow(
                title = stringResource(R.string.settings_rate_title),
                summary = stringResource(R.string.settings_rate_body),
                icon = ShortcutIcons.Star,
                onClick = actions.onRate,
            )
        }
        SettingsRow(
            title = stringResource(R.string.settings_feedback_title),
            summary = stringResource(R.string.settings_feedback_body),
            icon = ShortcutIcons.Mail,
            onClick = actions.onSendFeedback,
        )
        SettingsRow(
            title = stringResource(R.string.settings_nav_licenses_row),
            summary = stringResource(R.string.settings_nav_licenses_body),
            icon = Symbols.Description,
            onClick = { open(SettingsPage.Licenses) },
            showChevron = true,
        )
    }
}

// endregion

// region Licences

/** A full page with one expandable entry per licence, instead of a long cut-off dialog (SE-42). */
@Composable
private fun LicensesPage(actions: SettingsActions) {
    // B11: no group title repeating the page title.
    TonalRows(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        LicenseItem(
            key = "app",
            title = R.string.settings_license_app_title,
            summary = "GPL-3.0",
            body = R.string.settings_license_app_body,
            links = listOf(R.string.settings_nav_license_app_link to LICENSE_URL),
            onOpenLink = actions.onOpenLink,
        )
        LicenseItem(
            key = "fonts",
            title = R.string.settings_license_fonts_title,
            summary = "OFL-1.1, Apache-2.0",
            body = R.string.settings_nav_license_fonts_body,
            links = listOf(
                R.string.settings_nav_license_ofl_link to OFL_URL,
                R.string.settings_nav_license_apache_link to APACHE_URL,
            ),
            onOpenLink = actions.onOpenLink,
        )
        LicenseItem(
            key = "icons",
            title = R.string.settings_license_icons_title,
            summary = "Apache-2.0",
            body = R.string.settings_nav_license_icons_body,
            links = listOf(R.string.settings_nav_license_apache_link to APACHE_URL),
            onOpenLink = actions.onOpenLink,
        )
        LicenseItem(
            key = "libs",
            title = R.string.settings_license_libs_title,
            summary = "GPL-3.0, Apache-2.0",
            body = R.string.settings_license_libs_body,
            links = emptyList(),
            onOpenLink = actions.onOpenLink,
        )
    }
}

/** The public privacy policy page, opened in the browser from the About page. */
internal const val PRIVACY_POLICY_URL = "https://github.com/MyNameIsGlooboo/uMessage/blob/main/PRIVACY.md"

/** The project's licence, as published with the source. */
private const val LICENSE_URL = "https://github.com/MyNameIsGlooboo/uMessage/blob/main/LICENSE"

/** The official SIL Open Font License 1.1 text, for Google Sans Flex and Vazirmatn. */
private const val OFL_URL = "https://openfontlicense.org/open-font-license-official-text/"

/** The Apache License 2.0 text, for Roboto and Material Symbols. */
private const val APACHE_URL = "https://www.apache.org/licenses/LICENSE-2.0"

/** One row of the licences page: tap to expand the text and its "read the full text" links. */
@Composable
private fun LicenseItem(
    key: String,
    title: Int,
    summary: String,
    body: Int,
    links: List<Pair<Int, String>>,
    onOpenLink: (String) -> Unit,
) {
    var expanded by rememberSaveable(key) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) { expanded = !expanded }
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            androidx.compose.foundation.layout.Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = if (expanded) Symbols.ExpandLess else Symbols.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                Text(
                    stringResource(body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                links.forEach { (labelRes, url) ->
                    TextButton(onClick = { onOpenLink(url) }) { Text(stringResource(labelRes)) }
                }
            }
        }
    }
}

// endregion

// region Previews

private val previewState = SettingsUiState(
    spam = SpamSummary(UnknownSenderMode.SMART, blockedLast7Days = 12, blockedTotal = 148, restoredTotal = 3),
    sims = listOf(
        SimCard(1, 1, "Jio", "+91 98765 43210", isDefaultSms = true),
        SimCard(2, 2, "Airtel", null, isDefaultSms = false),
    ),
    lockScreenLabel = "Sender and message",
    showCharacterCounter = true,
    mmsLimitLabel = "Unlimited",
    useRecycleBin = true,
    recycleBinLabel = "4 messages",
    showDecisionLog = true,
    forwardRuleCount = 2,
    versionName = "1.0.0",
)

@Preview(name = "Settings light, dual SIM", showBackground = true, heightDp = 1400)
@Composable
private fun SettingsLightPreview() {
    UMessageTheme(darkTheme = false) { SettingsScreen(previewState, SettingsActions()) }
}

@Preview(name = "Settings dark", showBackground = true, heightDp = 1400)
@Composable
private fun SettingsDarkPreview() {
    UMessageTheme(darkTheme = true) { SettingsScreen(previewState, SettingsActions()) }
}

@Preview(name = "Settings RTL", showBackground = true, heightDp = 1400, locale = "fa")
@Composable
private fun SettingsRtlPreview() {
    UMessageTheme(darkTheme = false) { SettingsScreen(previewState, SettingsActions()) }
}

@Preview(name = "Settings single SIM, no permission", showBackground = true, heightDp = 1200)
@Composable
private fun SettingsSingleSimPreview() {
    UMessageTheme(darkTheme = false) {
        SettingsScreen(SettingsUiState(simPermissionGranted = false, versionName = "1.0.0"), SettingsActions())
    }
}

@Preview(name = "Settings typing page", showBackground = true, heightDp = 1200)
@Composable
private fun SettingsTypingPreview() {
    UMessageTheme(darkTheme = false) { SettingsScreen(previewState, SettingsActions(), SettingsPage.Typing) }
}

// endregion

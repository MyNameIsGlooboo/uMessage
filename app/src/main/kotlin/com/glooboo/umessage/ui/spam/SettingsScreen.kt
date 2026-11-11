package com.glooboo.umessage.ui.spam

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.glooboo.umessage.ui.components.ChoiceDialog
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.glooboo.umessage.R
import com.glooboo.umessage.spam.SpamSettings
import com.glooboo.umessage.spam.UnknownInboxNotify
import com.glooboo.umessage.spam.UnknownSenderMode
import com.glooboo.umessage.ui.components.SettingsGroup
import com.glooboo.umessage.ui.components.SettingsRadioRow
import com.glooboo.umessage.ui.components.SettingsRow
import com.glooboo.umessage.ui.components.SettingsSwitchRow
import com.glooboo.umessage.ui.icons.Symbols
import com.glooboo.umessage.ui.theme.UMessageTheme
import androidx.compose.ui.unit.Dp
import com.glooboo.umessage.ui.components.UMessageScaffold
import com.glooboo.umessage.ui.components.UMessageTopBar
import com.glooboo.umessage.ui.components.uiLocale
import androidx.compose.ui.platform.LocalContext
import com.glooboo.umessage.helpers.threadPrefs

@Immutable
class SettingsActions(
    val onBack: () -> Unit = {},
    val onOpenRules: () -> Unit = {},
    val onOpenBlocked: () -> Unit = {},
    val onMode: (UnknownSenderMode) -> Unit = {},
    val onPurgeDays: (Int) -> Unit = {},
    val onNotify: (Boolean) -> Unit = {},
    val onUnknownNotify: (UnknownInboxNotify) -> Unit = {},
    val onOpenLinkAction: (Boolean) -> Unit = {},
    val onOutsideRegion: (String) -> Unit = {},
    /** Null hides the "Why was it blocked?" row (no engine log in this build). */
    val onOpenDecisionLog: (() -> Unit)? = null,
)

@Composable
fun SettingsRoute(
    onBack: () -> Unit,
    onOpenRules: () -> Unit,
    onOpenBlocked: () -> Unit,
    onOpenDecisionLog: (() -> Unit)? = null,
) {
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val actions = remember(viewModel, onBack, onOpenRules, onOpenBlocked, onOpenDecisionLog) {
        SettingsActions(
            onBack = onBack,
            onOpenRules = onOpenRules,
            onOpenBlocked = onOpenBlocked,
            onMode = viewModel::setMode,
            onPurgeDays = viewModel::setPurgeDays,
            onNotify = viewModel::setNotifyOnBlock,
            onUnknownNotify = viewModel::setUnknownInboxNotify,
            onOpenLinkAction = viewModel::setOpenLinkAction,
            onOutsideRegion = viewModel::setBlockOutsideRegion,
            onOpenDecisionLog = onOpenDecisionLog,
        )
    }
    val snackbar = remember { SnackbarHostState() }
    val failedText = stringResource(R.string.spam_snack_failed)
    LaunchedEffect(viewModel) {
        viewModel.failureFlow.collect { snackbar.showSnackbar(failedText) }
    }
    SettingsScreen(state, actions, snackbar)
}

@Composable
fun SettingsScreen(state: SettingsUiState, actions: SettingsActions, snackbarHostState: SnackbarHostState? = null) {
    UMessageScaffold(
        snackbarHostState = snackbarHostState,
        topBar = {
            UMessageTopBar(title = stringResource(R.string.settings_nav_spam_page_title), onBack = actions.onBack)
        },
    ) { inner ->
        if (state.loading) {
            Column(Modifier.padding(top = inner.calculateTopPadding()).fillMaxWidth().padding(48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val label = stringResource(R.string.spam_loading)
                CircularProgressIndicator(Modifier.semantics { contentDescription = label })
            }
        } else {
            SettingsContent(state, actions, Modifier.padding(top = inner.calculateTopPadding()), inner.calculateBottomPadding())
        }
    }
}

@Composable
private fun SettingsContent(state: SettingsUiState, actions: SettingsActions, modifier: Modifier = Modifier, bottomInset: Dp = 0.dp) {
    val settings = state.settings
    // SE-23: choosing Strict first asks, because it also blocks verification codes.
    var confirmStrict by rememberSaveable { mutableStateOf(false) }
    var purgeDialog by rememberSaveable { mutableStateOf(false) }
    var unknownNotifyDialog by rememberSaveable { mutableStateOf(false) }
    var regionDialog by rememberSaveable { mutableStateOf(false) }
    var restoredOpen by rememberSaveable { mutableStateOf(false) }
    val chooseMode: (UnknownSenderMode) -> Unit = { mode ->
        if (mode == UnknownSenderMode.STRICT && settings.mode != UnknownSenderMode.STRICT) {
            confirmStrict = true
        } else {
            actions.onMode(mode)
        }
    }
    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 32.dp + bottomInset),
    ) {
        // U02: the one Spam protection page: the mode, the rules, retention, countries, links and alerts.
        SettingsGroup(
            title = stringResource(R.string.spam_settings_unknown_title),
            description = stringResource(R.string.spam_settings_unknown_body),
        ) {
            ModeChoice(
                UnknownSenderMode.STRICT, R.string.spam_mode_strict, R.string.spam_mode_strict_body,
                settings, chooseMode,
            )
            ModeChoice(
                UnknownSenderMode.SMART, R.string.spam_mode_smart, R.string.spam_mode_smart_body,
                settings, chooseMode,
                badge = stringResource(R.string.settings_nav_recommended),
            )
            ModeChoice(
                UnknownSenderMode.OFF, R.string.spam_mode_off, R.string.spam_mode_off_body,
                settings, chooseMode,
            )
        }
        if (settings.mode == UnknownSenderMode.STRICT) {
            StrictWarning(
                onReview = actions.onOpenBlocked,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
            )
        }

        SettingsGroup(stringResource(R.string.settings_f_group_rules)) {
            SettingsRow(
                title = stringResource(R.string.settings_spam_open_rules),
                summary = stringResource(R.string.settings_spam_open_rules_body),
                onClick = actions.onOpenRules,
                showChevron = true,
            )
            SettingsRow(
                title = stringResource(R.string.blocked_menu_restored),
                summary = stringResource(R.string.blocked_menu_restored_summary),
                onClick = { restoredOpen = true },
                showChevron = true,
            )
        }

        SettingsGroup(stringResource(R.string.spam_settings_cleanup_title)) {
            // SE-25: one dropdown-style row instead of chips that wrap onto a second line.
            SettingsRow(
                title = stringResource(R.string.settings_nav_purge_row),
                summary = stringResource(R.string.spam_purge_body),
                value = purgeLabel(settings.purgeAfterDays),
                onClick = { purgeDialog = true },
                showChevron = true,
            )
        }

        SettingsGroup(stringResource(R.string.region_group_title)) {
            SettingsRow(
                title = stringResource(R.string.region_title),
                summary = stringResource(R.string.region_summary),
                value = regionValue(settings.blockOutsideRegion),
                onClick = { regionDialog = true },
                showChevron = true,
            )
        }

        LinksGroup()

        CarrierReportGroup()

        SettingsGroup(stringResource(R.string.settings_nav_group_alerts)) {
            SettingsSwitchRow(
                title = stringResource(R.string.spam_notify_title),
                summary = stringResource(R.string.settings_nav_notify_body),
                checked = settings.notifyOnBlock,
                onCheckedChange = actions.onNotify,
            )
            SettingsRow(
                title = stringResource(R.string.unotify_title),
                summary = stringResource(R.string.unotify_summary),
                value = unknownNotifyLabel(settings.unknownInboxNotify),
                onClick = { unknownNotifyDialog = true },
                showChevron = true,
            )
            SettingsSwitchRow(
                title = stringResource(R.string.notif_open_link_title),
                summary = stringResource(R.string.notif_open_link_summary),
                checked = settings.openLinkAction,
                onCheckedChange = actions.onOpenLinkAction,
            )
        }

        // U45: the log is named Recent activity and linked once, from here.
        val openLog = actions.onOpenDecisionLog
        if (openLog != null) {
            SettingsGroup(stringResource(R.string.settings_ui_more)) {
                SettingsRow(
                    title = stringResource(R.string.settings_nav_decision_log),
                    summary = stringResource(R.string.settings_nav_decision_log_body),
                    onClick = openLog,
                    showChevron = true,
                )
            }
        }
    }

    if (restoredOpen) {
        val restored by RestoredLog.get(LocalContext.current).entries.collectAsStateWithLifecycle()
        RestoredSheet(restored, onDismiss = { restoredOpen = false })
    }
    if (confirmStrict) {
        AlertDialog(
            onDismissRequest = { confirmStrict = false },
            title = { Text(stringResource(R.string.settings_nav_strict_confirm_title)) },
            text = { Text(stringResource(R.string.settings_nav_strict_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmStrict = false
                    actions.onMode(UnknownSenderMode.STRICT)
                }) { Text(stringResource(R.string.settings_nav_strict_confirm_action)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmStrict = false }) {
                    Text(stringResource(R.string.settings_nav_strict_confirm_cancel))
                }
            },
        )
    }
    if (regionDialog) {
        CountryPicker(
            selected = settings.blockOutsideRegion,
            onSelect = { region ->
                regionDialog = false
                actions.onOutsideRegion(region)
            },
            onDismiss = { regionDialog = false },
        )
    }
    if (unknownNotifyDialog) {
        val choices = UnknownInboxNotify.entries
        ChoiceDialog(
            title = stringResource(R.string.unotify_title),
            options = choices.map { unknownNotifyLabel(it) },
            selectedIndex = choices.indexOf(settings.unknownInboxNotify),
            onSelect = { index ->
                unknownNotifyDialog = false
                actions.onUnknownNotify(choices[index])
            },
            onDismiss = { unknownNotifyDialog = false },
        )
    }
    if (purgeDialog) {
        val options = state.purgeOptions
        ChoiceDialog(
            title = stringResource(R.string.settings_nav_purge_row),
            options = options.map { purgeLabel(it) },
            selectedIndex = options.indexOf(settings.purgeAfterDays),
            onSelect = { index ->
                purgeDialog = false
                actions.onPurgeDays(options[index])
            },
            onDismiss = { purgeDialog = false },
        )
    }
}

@Composable
private fun LinksGroup() {
    val prefs = LocalContext.current.threadPrefs
    var locked by remember { mutableStateOf(prefs.riskyLinksLocked) }
    SettingsGroup(stringResource(R.string.i07_group_title)) {
        SettingsSwitchRow(
            title = stringResource(R.string.i07_lock_title),
            summary = stringResource(R.string.i07_lock_summary),
            checked = locked,
            onCheckedChange = {
                locked = it
                prefs.riskyLinksLocked = it
            },
        )
    }
}

@Composable
private fun regionValue(region: String): String =
    if (region.isBlank()) stringResource(R.string.region_off) else CountryOptions.nameOf(region, uiLocale())

@Composable
private fun unknownNotifyLabel(choice: UnknownInboxNotify): String = stringResource(
    when (choice) {
        UnknownInboxNotify.NOTIFY -> R.string.unotify_notify
        UnknownInboxNotify.SILENT -> R.string.unotify_silent
        UnknownInboxNotify.NONE -> R.string.unotify_none
    },
)

@Composable
private fun purgeLabel(days: Int): String =
    if (days == 0) stringResource(R.string.spam_purge_forever) else stringResource(R.string.spam_purge_days, days)

@Composable
private fun ModeChoice(
    mode: UnknownSenderMode,
    title: Int,
    body: Int,
    settings: SpamSettings,
    onChoose: (UnknownSenderMode) -> Unit,
    badge: String? = null,
) {
    SettingsRadioRow(
        title = stringResource(title),
        summary = stringResource(body),
        selected = settings.mode == mode,
        onSelect = { onChoose(mode) },
        badge = badge,
    )
}

@Composable
private fun StrictWarning(onReview: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        colors = CardDefaults.cardColors(containerColor = colors.errorContainer, contentColor = colors.onErrorContainer),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Symbols.Warning, contentDescription = null, modifier = Modifier.size(24.dp))
                Text(stringResource(R.string.spam_strict_warning_title), style = MaterialTheme.typography.titleMedium)
            }
            Text(stringResource(R.string.spam_strict_warning_body), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onReview, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.spam_strict_warning_action))
            }
        }
    }
}

// region Previews

@Composable
private fun PreviewHost(settings: SpamSettings) = UMessageTheme {
    SettingsScreen(SettingsUiState(loading = false, settings = settings), SettingsActions())
}

@Preview(name = "Settings - strict", showBackground = true, widthDp = 360, heightDp = 1100)
@Composable
private fun SettingsStrictPreview() = PreviewHost(SpamSettings(mode = UnknownSenderMode.STRICT))

@Preview(name = "Settings - smart", showBackground = true, widthDp = 360, heightDp = 1100)
@Composable
private fun SettingsSmartPreview() = PreviewHost(SpamSettings(mode = UnknownSenderMode.SMART, purgeAfterDays = 60))

@Preview(
    name = "Settings - dark", showBackground = true, widthDp = 360, heightDp = 1100,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun SettingsDarkPreview() = PreviewHost(SpamSettings(notifyOnBlock = true))

// endregion

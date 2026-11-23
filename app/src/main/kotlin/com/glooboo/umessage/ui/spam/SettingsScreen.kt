package com.glooboo.umessage.ui.spam

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import com.glooboo.umessage.ui.components.ConfirmDialog
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.glooboo.umessage.R
import com.glooboo.umessage.spam.AllowGroup
import com.glooboo.umessage.spam.SpamSettings
import com.glooboo.umessage.spam.UnknownInboxNotify
import com.glooboo.umessage.spam.UnknownSenderMode
import com.glooboo.umessage.ui.components.SettingsGroup
import com.glooboo.umessage.ui.components.SettingsRow
import com.glooboo.umessage.ui.components.SettingsSwitchRow
import com.glooboo.umessage.ui.onboarding.SortingCard
import com.glooboo.umessage.ui.theme.UMessageTheme
import androidx.compose.ui.unit.Dp
import com.glooboo.umessage.ui.components.UMessageScaffold
import com.glooboo.umessage.ui.components.UMessageTopBar
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import com.glooboo.umessage.helpers.threadPrefs

@Immutable
class SettingsActions(
    val onBack: () -> Unit = {},
    val onOpenRules: () -> Unit = {},
    val onPurgeDays: (Int) -> Unit = {},
    val onNotify: (Boolean) -> Unit = {},
    val onUnknownNotify: (UnknownInboxNotify) -> Unit = {},
    val onOpenLinkAction: (Boolean) -> Unit = {},
    /** Null hides the "Why was it blocked?" row (no engine log in this build). */
    val onOpenDecisionLog: (() -> Unit)? = null,
    /** Provider truth: the filter switch was tapped; it asks first (U11). */
    val onFilter: (Boolean) -> Unit = {},
    val onConfirmSwitch: () -> Unit = {},
    val onDismissSwitch: () -> Unit = {},
    /** Provider truth: the auto-delete warning was accepted or dismissed. */
    val onConfirmPurge: () -> Unit = {},
    val onDismissPurge: () -> Unit = {},
)

@Composable
fun SettingsRoute(
    onBack: () -> Unit,
    onOpenRules: () -> Unit,
    onOpenDecisionLog: (() -> Unit)? = null,
) {
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val actions = remember(viewModel, onBack, onOpenRules, onOpenDecisionLog) {
        SettingsActions(
            onBack = onBack,
            onOpenRules = onOpenRules,
            onPurgeDays = viewModel::setPurgeDays,
            onNotify = viewModel::setNotifyOnBlock,
            onUnknownNotify = viewModel::setUnknownInboxNotify,
            onOpenLinkAction = viewModel::setOpenLinkAction,
            onOpenDecisionLog = onOpenDecisionLog,
            onFilter = { on -> viewModel.switches.request(SortChange.Filter(on)) },
            onConfirmSwitch = viewModel.switches::confirm,
            onDismissSwitch = viewModel.switches::dismiss,
            onConfirmPurge = viewModel.purge::confirm,
            onDismissPurge = viewModel.purge::dismiss,
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
            UMessageTopBar(
                title = stringResource(R.string.settings_nav_spam_page_title),
                onBack = actions.onBack,
                titleLines = 2,
            )
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
    var purgeDialog by rememberSaveable { mutableStateOf(false) }
    var unknownNotifyDialog by rememberSaveable { mutableStateOf(false) }
    var restoredOpen by rememberSaveable { mutableStateOf(false) }
    val cardMode = rememberSortingCardMode(settings.shownMode())
    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 32.dp + bottomInset),
    ) {
        // U02: the one Spam protection page: the filter switch, the rules, retention, links and alerts.
        FilterSwitchGroup(settings.filterOn, cardMode, actions.onFilter)

        SettingsGroup(stringResource(R.string.settings_f_group_rules)) {
            SettingsRow(
                title = stringResource(R.string.settings_block_umessage_title),
                summary = stringResource(R.string.settings_block_umessage_body),
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
                summary = purgeSummary(settings.purgeAfterDays)?.let { stringResource(it) },
                value = purgeLabel(settings.purgeAfterDays),
                onClick = { purgeDialog = true },
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
    state.switchConfirm?.let { SwitchConfirmDialog(it, actions.onConfirmSwitch, actions.onDismissSwitch) }
    state.purgeWarning?.let { PurgeWarningDialog(it, actions.onConfirmPurge, actions.onDismissPurge) }
}

/**
 * Provider truth (4.6): the one spam filter switch, with what "on" and "off" do on its line and the sorting card
 * behind the circled i. The switch does not change at once: [onFilter] asks first (U11).
 */
@Composable
internal fun FilterSwitchGroup(filterOn: Boolean, cardMode: UnknownSenderMode?, onFilter: (Boolean) -> Unit) {
    val title = stringResource(R.string.spam_settings_unknown_title)
    SettingsGroup(
        title = title,
        description = stringResource(R.string.spam_settings_unknown_body),
        descriptionExtra = cardMode?.let { shown ->
            { SortingCard(mode = shown, containerColor = MaterialTheme.colorScheme.surface) }
        },
    ) {
        // G-16: the heading names the filter once; the switch says what on and off do.
        SettingsSwitchRow(
            title = stringResource(if (filterOn) R.string.pt_filter_on_summary else R.string.pt_filter_off_summary),
            checked = filterOn,
            onCheckedChange = onFilter,
        )
    }
}

/** What a switch confirm says. */
internal data class SwitchConfirmText(val title: String, val body: String, val confirmLabel: String)

/**
 * The texts of a [SwitchConfirm] (answer 18, U11): the number of chats that move, "No chat moves now." for none, and
 * no number at all while the count is unknown. Turning the code group off also says that codes then go to Spam.
 */
internal fun switchConfirmText(resources: Resources, confirm: SwitchConfirm): SwitchConfirmText =
    when (val change = confirm.change) {
        is SortChange.Filter -> SwitchConfirmText(
            title = resources.getString(
                if (change.on) R.string.pt_filter_on_confirm_title else R.string.pt_filter_off_confirm_title,
            ),
            body = countedBody(
                resources,
                confirm.chats,
                if (change.on) R.plurals.pt_filter_on_confirm_body else R.plurals.pt_filter_off_confirm_body,
                if (change.on) R.string.pt_filter_on_confirm_rest else R.string.pt_filter_off_confirm_rest,
            ),
            confirmLabel = resources.getString(if (change.on) R.string.cleanup_bin_confirm else R.string.pt_turn_off),
        )
        is SortChange.Group -> {
            val count = countedBody(resources, confirm.chats, R.plurals.pt_group_off_body, R.string.pt_group_off_rest)
            val note = resources.getString(R.string.pt_group_off_code_note).takeIf { change.group == AllowGroup.CODE }
            SwitchConfirmText(
                title = resources.getString(R.string.pt_group_off_title, resources.getString(groupTitle(change.group))),
                body = listOfNotNull(count, note).joinToString(PARAGRAPH),
                confirmLabel = resources.getString(R.string.pt_turn_off),
            )
        }
    }

/**
 * The confirm's text (lead decision): [counted] names the [chats] and holds the other sentences too; with no chat to
 * move it is "No chat moves now." and [rest]; while the count is unknown only [rest], the sentences without a number.
 */
private fun countedBody(resources: Resources, chats: Int?, @PluralsRes counted: Int, @StringRes rest: Int): String =
    when (chats) {
        null -> resources.getString(rest)
        0 -> resources.getString(R.string.pt_confirm_none_move) + " " + resources.getString(rest)
        else -> resources.getQuantityString(counted, chats, chats)
    }

private const val PARAGRAPH = "\n\n"

/** The name of a group rule. */
internal fun groupTitle(group: AllowGroup): Int = when (group) {
    AllowGroup.CODE -> R.string.pt_group_code_title
    AllowGroup.WROTE -> R.string.pt_group_wrote_title
}

/** Asks before a switch that moves chats is changed; nothing changes on Cancel. */
@Composable
internal fun SwitchConfirmDialog(confirm: SwitchConfirm, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val text = switchConfirmText(LocalResources.current, confirm)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text.title) },
        text = { Text(text.body, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(text.confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.spam_cancel)) } },
    )
}

/** Auto-delete removes texts from the phone itself, for every app: said before [days] is set (4.6). */
@Composable
internal fun PurgeWarningDialog(days: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ConfirmDialog(
        title = stringResource(R.string.pt_purge_warn_title),
        message = LocalResources.current.getQuantityString(R.plurals.pt_purge_warn_body, days, days),
        confirmLabel = stringResource(R.string.cleanup_bin_confirm),
        dismissLabel = stringResource(R.string.spam_cancel),
        destructive = true,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
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
private fun unknownNotifyLabel(choice: UnknownInboxNotify): String = stringResource(
    when (choice) {
        UnknownInboxNotify.NOTIFY -> R.string.unotify_notify
        UnknownInboxNotify.SILENT -> R.string.unotify_silent
        UnknownInboxNotify.NONE -> R.string.unotify_none
    },
)

/** L-4: "Off until you choose a number of days" only while no days are chosen; then the value alone says them. */
internal fun purgeSummary(days: Int): Int? = if (days == 0) R.string.spam_purge_body else null

@Composable
private fun purgeLabel(days: Int): String =
    if (days == 0) stringResource(R.string.spam_purge_forever) else stringResource(R.string.spam_purge_days, days)

// region Previews

@Composable
private fun PreviewHost(settings: SpamSettings) = UMessageTheme {
    SettingsScreen(SettingsUiState(loading = false, settings = settings), SettingsActions())
}

@Preview(name = "Settings - filter off", showBackground = true, widthDp = 360, heightDp = 1100)
@Composable
private fun SettingsFilterOffPreview() = PreviewHost(SpamSettings(filterOn = false))

@Preview(name = "Settings - filter on", showBackground = true, widthDp = 360, heightDp = 1100)
@Composable
private fun SettingsFilterOnPreview() = PreviewHost(SpamSettings(purgeAfterDays = 60))

@Preview(
    name = "Settings - dark", showBackground = true, widthDp = 360, heightDp = 1100,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun SettingsDarkPreview() = PreviewHost(SpamSettings(notifyOnBlock = true))

// endregion

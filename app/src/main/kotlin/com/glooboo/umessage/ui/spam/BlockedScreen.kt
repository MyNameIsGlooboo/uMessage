package com.glooboo.umessage.ui.spam

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.glooboo.umessage.R
import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.SpamRule
import com.glooboo.umessage.spam.SpamStats
import com.glooboo.umessage.spam.UnknownSenderMode
import com.glooboo.umessage.ui.components.ConfirmDialog
import com.glooboo.umessage.ui.components.EmptyState
import com.glooboo.umessage.ui.components.InfoChip
import com.glooboo.umessage.ui.components.SelectionTopBar
import com.glooboo.umessage.ui.components.UMessageFilterChip
import com.glooboo.umessage.ui.components.UMessageScaffold
import com.glooboo.umessage.ui.components.UMessageTopBar
import com.glooboo.umessage.ui.components.displayNumber
import com.glooboo.umessage.ui.components.isolateNumbers
import com.glooboo.umessage.ui.components.localizedNumber
import com.glooboo.umessage.ui.components.rememberReduceMotion
import com.glooboo.umessage.ui.components.uiLocale
import com.glooboo.umessage.ui.icons.Symbols
import com.glooboo.umessage.ui.theme.UMessageTheme
import kotlinx.coroutines.launch

/** Stateful entry point: wires the ViewModel, undo snackbar and one-shot events. */
@Composable
fun BlockedRoute(onBack: (() -> Unit)?, onOpenRules: () -> Unit, onOpenSettings: () -> Unit) {
    val viewModel: BlockedViewModel = viewModel(factory = BlockedViewModel.Factory)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val groups by rememberUpdatedState(state.groups)
    BlockedEvents(viewModel, snackbar, onOpenRules)
    val actions = remember(viewModel, onBack, onOpenRules, onOpenSettings, context) {
        BlockedActions(
            onBack = onBack,
            onOpenRules = onOpenRules,
            onOpenSettings = onOpenSettings,
            onOpenSender = { address -> context.startActivity(BlockedThreadActivity.intent(context, address)) },
            onToggleSelected = viewModel::toggleSelected,
            onClearSelection = viewModel::clearSelection,
            onSelectAll = viewModel::selectAll,
            onNotSpam = viewModel::notSpam,
            onDelete = viewModel::delete,
            onAnswerAllow = viewModel::answerAllow,
            onIncludeSimilar = viewModel::setIncludeSimilar,
            onSetFilter = viewModel::setFilter,
            onReportSpam = { selected -> scope.launch { reportToCarrier(context, groups, selected, snackbar) } },
        )
    }
    BlockedScreen(state = state, snackbarHostState = snackbar, actions = actions)
}

/**
 * The snackbars of the Blocked tab's and the sender thread's one-shot events, with Undo (or View rule) where it
 * applies. [onSettle] hears how a restore or delete snackbar ended: true when the action stays, false on Undo.
 */
@Composable
internal fun BlockedEvents(
    viewModel: BlockedViewModel,
    snackbar: SnackbarHostState,
    onOpenRules: () -> Unit,
    onSettle: (committed: Boolean) -> Unit = {},
) {
    val resources = LocalResources.current
    val context = LocalContext.current
    val undoLabel = stringResource(R.string.spam_undo)
    val viewRuleLabel = stringResource(R.string.e_view_rule)
    val failedText = stringResource(R.string.spam_snack_failed)
    val openRules by rememberUpdatedState(onOpenRules)
    val settled by rememberUpdatedState(onSettle)
    LaunchedEffect(viewModel) {
        // After a rotation the previous snackbar was cancelled unanswered; offer its Undo again.
        viewModel.replayPending()
        viewModel.eventFlow.collect { event ->
            snackbar.currentSnackbarData?.dismiss()
            launch {
                val dualSim = viewModel.state.value.sims.size >= 2
                val text = eventText(resources, event, failedText, dualSim) { number ->
                    isolateNumbers(displayNumber(context, number))
                }
                when (event) {
                    is BlockedEvent.Restored -> {
                        val result = snackbar.showSnackbar(text, undoLabel, duration = SnackbarDuration.Long)
                        if (result == SnackbarResult.ActionPerformed) {
                            viewModel.undoRestore(event.token)
                        } else {
                            viewModel.commitRestore(event.token)
                        }
                        settled(result != SnackbarResult.ActionPerformed)
                    }
                    is BlockedEvent.ModeChanged -> {
                        val result = snackbar.showSnackbar(text, undoLabel, duration = SnackbarDuration.Long)
                        if (result == SnackbarResult.ActionPerformed) viewModel.undoMode(event.previous)
                    }
                    is BlockedEvent.Deleted -> {
                        val result = snackbar.showSnackbar(text, undoLabel, duration = SnackbarDuration.Long)
                        if (result == SnackbarResult.ActionPerformed) {
                            viewModel.undoDelete(event.token)
                        } else {
                            viewModel.commitDelete(event.token)
                        }
                        settled(result != SnackbarResult.ActionPerformed)
                    }
                    // BR-49: says which rule "Always allow" made, and opens the Rules screen on the Allowed tab.
                    is BlockedEvent.AllowRulesAdded -> {
                        val result = snackbar.showSnackbar(text, viewRuleLabel, duration = SnackbarDuration.Long)
                        if (result == SnackbarResult.ActionPerformed) openRules()
                    }
                    BlockedEvent.Failed, is BlockedEvent.RestoreFailed ->
                        snackbar.showSnackbar(text, duration = SnackbarDuration.Long)
                }
            }
        }
    }
}

/** The snackbar line of [event]. */
private fun eventText(
    resources: android.content.res.Resources,
    event: BlockedEvent,
    failedText: String,
    dualSim: Boolean,
    shownNumber: (String) -> String,
): String =
    when (event) {
        is BlockedEvent.Restored -> restoredSnackText(resources, event)
        is BlockedEvent.ModeChanged ->
            resources.getString(R.string.blocked_snack_mode, resources.getString(blockedModeLabel(event.mode)))
        is BlockedEvent.Deleted -> resources.getQuantityString(R.plurals.spam_snack_deleted, event.count, event.count)
        is BlockedEvent.AllowRulesAdded -> allowedSnackText(resources, event.addresses.map(shownNumber), dualSim)
        BlockedEvent.Failed -> failedText
        is BlockedEvent.RestoreFailed ->
            resources.getQuantityString(R.plurals.spam_snack_restore_failed, event.count, event.count)
    }

/**
 * The "Always allow" snackbar (BR-49). The rule covers every SIM, but "on both SIMs" is only said on a dual-SIM phone,
 * where it tells the user something.
 */
internal fun allowedSnackText(
    resources: android.content.res.Resources,
    addresses: List<String>,
    dualSim: Boolean,
): String = when {
    addresses.size == 1 && dualSim -> resources.getString(R.string.e_snack_allowed_one, addresses.single())
    addresses.size == 1 -> resources.getString(R.string.e_snack_allowed_one_single_sim, addresses.single())
    dualSim -> resources.getQuantityString(R.plurals.e_snack_allowed_many, addresses.size, addresses.size)
    else -> resources.getQuantityString(R.plurals.e_snack_allowed_many_single_sim, addresses.size, addresses.size)
}

/**
 * The Not spam snackbar. Says plainly when picture messages came back without their pictures, or not at all (SU-17),
 * instead of counting them as restored.
 */
internal fun restoredSnackText(resources: android.content.res.Resources, event: BlockedEvent.Restored): String {
    val restored = when {
        event.allowed > 0 && event.count > 0 ->
            resources.getQuantityString(R.plurals.spam_snack_restored_allowed, event.allowed, event.allowed)
        event.allowed > 0 -> resources.getQuantityString(R.plurals.spam_snack_allowed_only, event.allowed, event.allowed)
        event.count > 0 -> resources.getQuantityString(R.plurals.spam_snack_restored_to_inbox, event.count, event.count)
        else -> null
    }
    val lost = when {
        event.mediaLost <= 0 -> null
        event.count == 0 ->
            resources.getQuantityString(R.plurals.spam_snack_media_lost_nothing, event.mediaLost, event.mediaLost)
        else -> resources.getQuantityString(R.plurals.spam_snack_media_lost, event.mediaLost, event.mediaLost)
    }
    return listOfNotNull(restored, lost).joinToString(" ").ifEmpty {
        resources.getQuantityString(R.plurals.spam_snack_restored_to_inbox, event.count, event.count)
    }
}

/**
 * The Blocked tab (owner notes r3): the one-line protection summary, the filter chips, then one row per sender
 * drawn like the Messages tab.
 * Tapping a row opens the sender's thread; long press selects.
 */
@Composable
fun BlockedScreen(
    state: BlockedUiState,
    snackbarHostState: SnackbarHostState,
    actions: BlockedActions,
) {
    var confirmDeleteAll by rememberSaveable { mutableStateOf(false) }
    var restoredOpen by rememberSaveable { mutableStateOf(false) }
    var confirmReport by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = state.selecting, onBack = actions.onClearSelection)
    UMessageScaffold(
        topBar = {
            BlockedTopBar(
                state, actions,
                onDeleteAll = { confirmDeleteAll = true },
                onReport = { confirmReport = true },
                onOpenRestoredLog = { restoredOpen = true },
            )
        },
        snackbarHostState = snackbarHostState,
    ) { inner ->
        if (state.loading) {
            Box(
                Modifier.fillMaxSize().padding(top = inner.calculateTopPadding()),
                contentAlignment = Alignment.Center,
            ) {
                val label = stringResource(R.string.spam_loading)
                CircularProgressIndicator(Modifier.semantics { contentDescription = label })
            }
        } else {
            BlockedList(
                state = state,
                actions = actions,
                modifier = Modifier.padding(top = inner.calculateTopPadding()),
                bottomInset = inner.calculateBottomPadding(),
            )
        }
    }
    if (confirmDeleteAll) {
        val total = state.groups.sumOf { it.messages.size }
        ConfirmDialog(
            title = stringResource(R.string.spam_ui_delete_all_title),
            message = pluralStringResource(R.plurals.spam_ui_delete_all_message, total, total),
            confirmLabel = stringResource(R.string.screens_delete),
            destructive = true,
            onConfirm = {
                confirmDeleteAll = false
                actions.onDelete(state.groups.map { it.address }.toSet())
            },
            onDismiss = { confirmDeleteAll = false },
        )
    }
    if (confirmReport) {
        CarrierReportDialog(
            number = CarrierReport(LocalContext.current).number,
            onConfirm = {
                confirmReport = false
                actions.onReportSpam(state.selected)
                actions.onClearSelection()
            },
            onDismiss = { confirmReport = false },
        )
    }
    if (restoredOpen) RestoredSheet(state.restored, onDismiss = { restoredOpen = false })
    state.allowPrompt?.let { AllowDialog(it, actions.onAnswerAllow, actions.onIncludeSimilar) }
}

/**
 * Asked after "Not spam" (T12 / SU-16): the messages go back to the inbox either way; allowing the senders is a
 * separate choice, because an allow rule beats every block rule. Sender names that anyone can send under get a
 * warning. Dismissing it means "Not now".
 */
@Composable
internal fun AllowDialog(
    prompt: AllowPrompt,
    onAnswer: (Long, Boolean) -> Unit,
    onIncludeSimilar: (Long, Boolean) -> Unit = { _, _ -> },
) {
    val count = prompt.addresses.size
    ConfirmDialog(
        title = pluralStringResource(R.plurals.spam_allow_title, count, count),
        message = when {
            count != 1 -> stringResource(R.string.spam_allow_body_many)
            else -> stringResource(
                allowBodyForOneSender(prompt.messageCount),
                isolateNumbers(displayNumber(prompt.addresses.single())),
            )
        },
        confirmLabel = stringResource(R.string.spam_allow_confirm),
        dismissLabel = stringResource(R.string.spam_allow_dismiss),
        onConfirm = { onAnswer(prompt.token, true) },
        onDismiss = { onAnswer(prompt.token, false) },
    ) {
        AllowSimilarOption(prompt, onIncludeSimilar, onEdit = { onAnswer(prompt.token, false) })
        if (prompt.spoofable.isNotEmpty()) {
            val names = prompt.spoofable.take(3).joinToString(", ")
            Row(
                modifier = Modifier.padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Symbols.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    stringResource(R.string.spam_allow_spoof_warning, names),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** "The message goes back..." or "The messages go back...", by how many of one sender's messages were moved. */
internal fun allowBodyForOneSender(messageCount: Int): Int =
    if (messageCount == 1) R.string.r3b_allow_body_sender_one_message else R.string.r3b_allow_body_sender_many_messages

/**
 * The tab's bar. While selecting: Select all, Move to inbox and Delete. "Block sender" is gone (owner notes r3): the
 * senders here are blocked already, so it only confused.
 */
@Composable
private fun BlockedTopBar(
    state: BlockedUiState,
    actions: BlockedActions,
    onDeleteAll: () -> Unit,
    onReport: () -> Unit = {},
    onOpenRestoredLog: () -> Unit = {},
) {
    if (state.selecting) {
        val allSelected = state.selected.size == state.groups.size
        // I32: only for one sender at a time, and only once the user has entered their carrier's number.
        val context = LocalContext.current
        val canReport = state.selected.size == 1 && CarrierReport(context).number.isNotEmpty()
        SelectionTopBar(count = state.selected.size, onClose = actions.onClearSelection) {
            if (!allSelected) {
                IconButton(onClick = actions.onSelectAll) {
                    Icon(Symbols.SelectAll, contentDescription = stringResource(R.string.spam_action_select_all))
                }
            }
            IconButton(onClick = { actions.onNotSpam(state.selected) }) {
                Icon(Symbols.Inbox, contentDescription = stringResource(R.string.blocked_action_move_inbox))
            }
            if (canReport) {
                IconButton(onClick = onReport) {
                    Icon(Symbols.Warning, contentDescription = stringResource(R.string.carrier_report_action))
                }
            }
            IconButton(onClick = { actions.onDelete(state.selected) }) {
                Icon(Symbols.Delete, contentDescription = stringResource(R.string.spam_action_delete))
            }
        }
    } else {
        var menuOpen by remember { mutableStateOf(false) }
        UMessageTopBar(title = stringResource(R.string.blocked_title), onBack = actions.onBack) {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Symbols.MoreVert, contentDescription = stringResource(R.string.spam_more_options))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    // The only top-bar action: spam settings, rules and the restored list live in this menu.
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.spam_blocked_open_settings)) },
                        leadingIcon = { Icon(Symbols.Settings, contentDescription = null) },
                        onClick = { menuOpen = false; actions.onOpenSettings() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.settings_spam_open_rules)) },
                        leadingIcon = { Icon(Symbols.Rule, contentDescription = null) },
                        onClick = { menuOpen = false; actions.onOpenRules() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.blocked_menu_restored)) },
                        leadingIcon = { Icon(Symbols.RestoreFromTrash, contentDescription = null) },
                        onClick = { menuOpen = false; onOpenRestoredLog() },
                    )
                    HorizontalDivider()
                    // Destructive entry, in the error color (BR-07). It asks before deleting.
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.spam_ui_delete_all)) },
                        leadingIcon = { Icon(Symbols.Delete, contentDescription = null) },
                        colors = MenuDefaults.itemColors(
                            textColor = MaterialTheme.colorScheme.error,
                            leadingIconColor = MaterialTheme.colorScheme.error,
                        ),
                        enabled = state.groups.isNotEmpty(),
                        onClick = { menuOpen = false; onDeleteAll() },
                    )
                }
            }
        }
    }
}

@Composable
private fun BlockedList(
    state: BlockedUiState,
    actions: BlockedActions,
    modifier: Modifier = Modifier,
    bottomInset: Dp = 0.dp,
) {
    val reduceMotion = rememberReduceMotion()
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = 96.dp + bottomInset),
    ) {
        item(key = "stats") {
            BlockedLine(state.stats, state.mode, onOpenSettings = actions.onOpenSettings)
        }
        if (state.heldCount > 0) {
            item(key = "tip") { BlockedTip() }
            if (BlockedFilters.rowShown(state.filter, state.filterCounts)) {
                item(key = "filters") { BlockedFilterRow(state, actions.onSetFilter) }
            }
        }
        when {
            // Nothing held: say what uMessage is doing, so a fresh install does not look switched off (ID-17).
            state.heldCount == 0 -> item(key = "empty") { BlockedEmpty(state.mode) }
            state.groups.isEmpty() -> item(key = "filter-empty") { FilterEmpty(actions) }
            else -> items(state.groups, key = { it.address }) { group ->
                BlockedSenderRow(
                    group = group,
                    sims = state.sims,
                    rules = state.rules,
                    selected = group.address in state.selected,
                    selecting = state.selecting,
                    actions = actions,
                    reduceMotion = reduceMotion,
                    modifier = Modifier.animateItem(
                        fadeInSpec = if (reduceMotion) null else tween(150),
                        placementSpec = if (reduceMotion) snap() else spring(),
                        fadeOutSpec = if (reduceMotion) null else tween(150),
                    ),
                )
            }
        }
    }
}

/** Text only (U48): the line above has the mode, and it opens the settings. */
@Composable
private fun BlockedEmpty(mode: UnknownSenderMode) {
    EmptyState(
        iconRes = R.drawable.ic_spam_shield_check,
        title = stringResource(R.string.spam_blocked_empty_title),
        body = stringResource(R.string.w3b_empty_body, stringResource(blockedModeLabel(mode))),
    )
}

@Composable
private fun FilterEmpty(actions: BlockedActions) {
    EmptyState(
        iconRes = R.drawable.ic_spam_shield,
        title = stringResource(R.string.w3b_filter_none_title),
        body = stringResource(R.string.w3b_filter_none_body),
        action = {
            OutlinedButton(
                onClick = { actions.onSetFilter(BlockedFilter.All) },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.w3b_filter_show_all)) }
        },
    )
}

/**
 * Chips under the summary line (ID-14): All, why it was held, link, and one per SIM when there are two.
 * Each count is the number of senders (rows) the chip would list, over every held message, so "All 3" matches
 * the 3 rows below it. The chosen chip is always scrolled into view, in either direction (bug 6), so a filter
 * left on from an earlier visit is never hidden off the edge.
 */
@Composable
private fun BlockedFilterRow(state: BlockedUiState, onSetFilter: (BlockedFilter) -> Unit) {
    val locale = uiLocale()
    val options = filterOptions(state)
    val groupLabel = stringResource(R.string.w3b_filter_group)
    val chosen = remember { BringIntoViewRequester() }
    LaunchedEffect(state.filter) { runCatching { chosen.bringIntoView() } }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .semantics { contentDescription = groupLabel },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEach { (option, label) ->
            val count = state.filterCounts[option] ?: 0
            val selected = option == state.filter
            // A chip that shows nothing, or only what All already lists, is hidden (U31), except the chosen one.
            if (!BlockedFilters.chipShown(option, state.filter, state.filterCounts)) return@forEach
            UMessageFilterChip(
                selected = selected,
                onClick = { onSetFilter(option) },
                label = stringResource(R.string.w3b_filter_with_count, label, localizedNumber(count, locale)),
                modifier = if (selected) Modifier.bringIntoViewRequester(chosen) else Modifier,
            )
        }
    }
}

/** The chips of [BlockedFilterRow] in order, with their labels. */
@Composable
private fun filterOptions(state: BlockedUiState): List<Pair<BlockedFilter, String>> {
    val locale = uiLocale()
    val simSlots = state.sims.associate { it.subscriptionId to it.slot }
    val sims = state.filterCounts.keys.filterIsInstance<BlockedFilter.Sim>()
        .sortedBy { simSlots[it.subscriptionId] ?: Int.MAX_VALUE }
    return buildList {
        add(BlockedFilter.All to stringResource(R.string.w3b_filter_all))
        add(BlockedFilter.UnknownSender to stringResource(R.string.w3b_filter_unknown))
        add(BlockedFilter.MyRules to stringResource(R.string.w3b_filter_rules))
        add(BlockedFilter.HasLink to stringResource(R.string.w3b_filter_link))
        sims.forEach { sim ->
            val slot = simSlots[sim.subscriptionId] ?: return@forEach
            add(sim to stringResource(R.string.w3b_filter_sim, localizedNumber(slot, locale)))
        }
    }
}

/**
 * Why a message was blocked. A rule block names the rule (SU-22), e.g. "Rule: keyword "free"", and opens the rules
 * when [onOpenRules] is given.
 */
@Composable
fun ReasonBadge(
    message: BlockedMessage,
    rules: Map<Long, SpamRule>,
    modifier: Modifier = Modifier,
    onOpenRules: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val text = reasonText(message, rules)
    when (message.reason) {
        DecisionReason.BLOCK_RULE -> {
            val openLabel = stringResource(R.string.spam_reason_open_rules)
            InfoChip(
                text,
                if (onOpenRules == null) {
                    modifier
                } else {
                    modifier.clip(RoundedCornerShape(8.dp)).clickable(onClickLabel = openLabel, onClick = onOpenRules)
                },
                colors.errorContainer,
                colors.onErrorContainer,
            )
        }
        DecisionReason.UNKNOWN_SENDER, DecisionReason.FOREIGN_NUMBER ->
            InfoChip(text, modifier, colors.tertiaryContainer, colors.onTertiaryContainer)
        else -> InfoChip(text, modifier)
    }
}

/** The words of [ReasonBadge], also used as the quiet reason line of a sender row. */
@Composable
internal fun reasonText(message: BlockedMessage, rules: Map<Long, SpamRule>): String {
    val resources = LocalResources.current
    return when (message.reason) {
        DecisionReason.BLOCK_RULE ->
            remember(message, rules, resources) { ruleReasonText(resources, message, rules) }
                ?: stringResource(R.string.spam_reason_block_rule)
        DecisionReason.UNKNOWN_SENDER -> stringResource(
            if (BlockedHints.hasLink(message.body)) {
                R.string.blocked_reason_unknown_link
            } else {
                R.string.spam_reason_unknown_sender
            },
        )
        DecisionReason.FOREIGN_NUMBER -> stringResource(R.string.region_reason_foreign)
        else -> stringResource(R.string.spam_reason_other)
    }
}

/** Slot label for a message's SIM, or null when it should not be shown (single or unknown SIM). */
@Composable
internal fun simLabel(sims: List<SimInfo>, subscriptionId: Int): String? {
    if (sims.size < 2) return null
    val sim = sims.firstOrNull { it.subscriptionId == subscriptionId } ?: return null
    return stringResource(R.string.spam_sim_label, sim.slot)
}

/** Left in LTR and right in RTL, whatever script the text itself is in. */
@Composable
internal fun startAlign(): TextAlign =
    if (LocalLayoutDirection.current == LayoutDirection.Rtl) TextAlign.Right else TextAlign.Left

/**
 * The held-because sentence, for every message alike: a message held by a rule says so too, and its chip names the
 * rule, so a group mixing rule and unknown-sender messages reads the same on each one.
 */
internal fun heldWhyText(why: HeldWhy): Int = when (why) {
    HeldWhy.UNKNOWN_WITH_LINK -> R.string.w3b_why_link
    HeldWhy.UNKNOWN -> R.string.w3b_why_unknown
    HeldWhy.RULE -> R.string.w3b_why_rule
}

// region Previews

private val previewSims = listOf(SimInfo(1, 1, "Carrier A"), SimInfo(2, 2, "Carrier B"))
private const val PREVIEW_PERSIAN_SENDER = "+15550001234"
private val previewPersian = BlockedMessage(
    100, PREVIEW_PERSIAN_SENDER, "سلام، کد تایید شما ۴۸۲۹ است", 1, false,
    System.currentTimeMillis() - 30 * 60_000L, DecisionReason.UNKNOWN_SENDER,
)
private fun previewState(selected: Set<String> = emptySet(), empty: Boolean = false) = BlockedUiState(
    loading = false,
    groups = if (empty) emptyList() else groupBySender(SampleData.blocked() + previewPersian),
    stats = SpamStats(blockedTotal = 46, blockedLast7Days = 5, restoredTotal = 3),
    selected = selected,
    sims = previewSims,
)

@Composable
private fun PreviewHost(state: BlockedUiState, rtl: Boolean = false) = UMessageTheme {
    val direction = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
    CompositionLocalProvider(LocalLayoutDirection provides direction) {
        BlockedScreen(state, remember { SnackbarHostState() }, BlockedActions())
    }
}

@Preview(name = "Blocked - list", showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun BlockedListPreview() = PreviewHost(previewState())

@Preview(name = "Blocked - selecting", showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun BlockedSelectingPreview() = PreviewHost(previewState(selected = setOf("VM-HDFCBK")))

@Preview(name = "Blocked - empty", showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun BlockedEmptyPreview() = PreviewHost(previewState(empty = true))

@Preview(
    name = "Blocked - dark", showBackground = true, widthDp = 360, heightDp = 780,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun BlockedDarkPreview() = PreviewHost(previewState())

@Preview(name = "Blocked - RTL", showBackground = true, widthDp = 360, heightDp = 780, locale = "fa")
@Composable
private fun BlockedRtlPreview() = PreviewHost(previewState(), rtl = true)

@Preview(name = "Blocked - large font", showBackground = true, widthDp = 360, heightDp = 780, fontScale = 1.3f)
@Composable
private fun BlockedLargeFontPreview() = PreviewHost(previewState())

// endregion

package com.glooboo.umessage.ui.spam

import org.fossify.commons.extensions.appLockManager
import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.spam.ForwardRule
import com.glooboo.umessage.spam.ForwardSim
import com.glooboo.umessage.spam.ForwardStatus
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.ui.components.ConfirmDialog
import com.glooboo.umessage.ui.components.EmptyState
import com.glooboo.umessage.ui.components.FabListBottomPadding
import com.glooboo.umessage.ui.components.GroupShape
import com.glooboo.umessage.ui.components.InfoChip
import com.glooboo.umessage.ui.components.displayNumber
import com.glooboo.umessage.ui.components.formatClockTime
import com.glooboo.umessage.ui.components.isolateNumbers
import com.glooboo.umessage.ui.components.localizeDigits
import com.glooboo.umessage.ui.components.localizedNumber
import com.glooboo.umessage.ui.components.uiLocale
import com.glooboo.umessage.ui.icons.Symbols
import kotlinx.coroutines.delay

@Immutable
class ForwardActions(
    val onAdd: () -> Unit = {},
    val onEdit: (ForwardRule) -> Unit = {},
    val onToggle: (ForwardRule, Boolean) -> Unit = { _, _ -> },
    /** "Delete rule" in the edit sheet; the list then offers Undo. */
    val onDeleteFromForm: () -> Unit = {},
    val onUndoDelete: (ForwardRule) -> Unit = {},
    val onDeletedShown: () -> Unit = {},
    /** "Send test" in the editor: ask, then send or cancel. */
    val onAskTest: () -> Unit = {},
    val onConfirmTest: () -> Unit = {},
    val onCancelTest: () -> Unit = {},
    val onDismissForm: () -> Unit = {},
    val onSenderType: (RuleType?) -> Unit = {},
    val onSenderPattern: (String) -> Unit = {},
    val onBodyType: (RuleType?) -> Unit = {},
    val onBodyPattern: (String) -> Unit = {},
    val onUseWindow: (Boolean) -> Unit = {},
    val onWindowStart: (Int) -> Unit = {},
    val onWindowEnd: (Int) -> Unit = {},
    val onScope: (Int?) -> Unit = {},
    val onSendFrom: (ForwardSim) -> Unit = {},
    val onNote: (String) -> Unit = {},
    val onTarget: (Int, String) -> Unit = { _, _ -> },
    val onAddTarget: () -> Unit = {},
    val onRemoveTarget: (Int) -> Unit = {},
    val onIncludeCodes: (Boolean) -> Unit = {},
    val onConfirmCodes: () -> Unit = {},
    val onCancelCodes: () -> Unit = {},
    val onSubmit: () -> Unit = {},
    val onNoLockContinue: () -> Unit = {},
    val onNoLockSetUp: () -> Unit = {},
    val onDismissChangeFailed: () -> Unit = {},
    val onConfirmBroad: () -> Unit = {},
    val onCancelBroad: () -> Unit = {},
    /** Starts a new rule with this text to look for (the "Forward bank alerts" start). */
    val onAddForText: (String) -> Unit = {},
    /** "Hide" on the one-time "Forwarded tab added" message. */
    val onHideForwardedTab: () -> Unit = {},
    /** The one-time message went away (hidden, dismissed or timed out). */
    val onForwardedTabHintDone: () -> Unit = {},
)

/**
 * The Forward tab: the rules, or an empty state with the note on what forwarding does and costs (said once here and
 * once in the editor, P2-09).
 */
@Composable
fun ForwardRulesContent(state: ForwardUiState, actions: ForwardActions, bottomInset: Dp) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("forward_list"),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottomInset + FabListBottomPadding,
        ),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (!state.loading && state.rules.isEmpty()) {
            item(key = "forward-intro") { ForwardIntro() }
            item(key = "forward-empty") {
                val bankWord = stringResource(R.string.forward2_template_bank_word)
                EmptyState(
                    icon = Symbols.Forward,
                    title = stringResource(R.string.forward2_empty_title),
                    body = stringResource(R.string.forward2_empty_body),
                    action = {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilledTonalButton(onClick = { actions.onAddForText(bankWord) }) {
                                Text(stringResource(R.string.forward2_template_bank))
                            }
                            OutlinedButton(onClick = actions.onAdd) {
                                Text(stringResource(R.string.forward2_template_sender))
                            }
                        }
                    },
                )
            }
        }
        itemsIndexed(state.rules, key = { _, rule -> rule.id }) { index, rule ->
            ForwardRow(rule, groupShapeOf(index, state.rules.size), state.sims, state.stats[rule.id], actions)
        }
    }
}

/** One quiet note, not a warning: what forwarding costs, and that blocked spam is never forwarded. */
@Composable
private fun ForwardIntro() {
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            stringResource(R.string.forward_paid_note),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.forward2_intro_reassure),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun groupShapeOf(index: Int, size: Int): RoundedCornerShape {
    val corners = GroupShape.corners(index, size)
    return RoundedCornerShape(
        topStart = corners.topDp.dp, topEnd = corners.topDp.dp,
        bottomEnd = corners.bottomDp.dp, bottomStart = corners.bottomDp.dp,
    )
}

/** What a rule reacts to, e.g. "From: +1555…" and "Text: bank". */
@Composable
private fun conditionLines(rule: ForwardRule): List<String> = buildList {
    if (rule.senderType != null) {
        val sender = if (rule.senderType == RuleType.NUMBER) {
            isolateNumbers(displayNumber(rule.senderPattern))
        } else {
            rule.senderPattern
        }
        add(stringResource(R.string.forward_row_from, "${ruleTypeLabel(rule.senderType)} $sender"))
    } else {
        add(stringResource(R.string.forward_row_any_sender))
    }
    if (rule.bodyType != null) {
        add(stringResource(R.string.forward_row_text, "${ruleTypeLabel(rule.bodyType)} ${rule.bodyPattern}"))
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun ForwardRow(
    rule: ForwardRule,
    shape: RoundedCornerShape,
    sims: List<SimInfo>,
    stats: ForwardRuleStats?,
    actions: ForwardActions,
) {
    val colors = MaterialTheme.colorScheme
    val editLabel = stringResource(R.string.forward_edit_rule)
    val switchLabel = stringResource(R.string.forward_rule_switch)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(onClickLabel = editLabel) { actions.onEdit(rule) }
            .testTag("forward_rule_${rule.id}"),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainer),
        shape = shape,
    ) {
        Row(
            Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (rule.enabled) colors.primaryContainer else colors.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Symbols.Forward,
                    contentDescription = null,
                    tint = if (rule.enabled) colors.onPrimaryContainer else colors.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                conditionLines(rule).forEach {
                    Text(
                        it, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    stringResource(
                        R.string.forward_row_to,
                        rule.targets.map { isolateNumbers(displayNumber(it)) }.joinToString(", "),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (rule.includeCodes) {
                        InfoChip(
                            stringResource(R.string.forward_chip_codes),
                            container = colors.errorContainer,
                            content = colors.onErrorContainer,
                            icon = Symbols.Warning,
                        )
                    }
                    if (rule.hasWindow) {
                        val start = formatMinutes(rule.windowStart!!)
                        InfoChip(stringResource(R.string.forward_chip_window, start, formatMinutes(rule.windowEnd!!)))
                    }
                    if (sims.size >= 2) {
                        rule.subscriptionId?.let { id ->
                            sims.firstOrNull { it.subscriptionId == id }?.let {
                                InfoChip(stringResource(R.string.spam_sim_label, it.slot))
                            }
                        }
                        when (rule.sendFrom) {
                            ForwardSim.SIM1 -> InfoChip(stringResource(R.string.forward_chip_send_from, 1))
                            ForwardSim.SIM2 -> InfoChip(stringResource(R.string.forward_chip_send_from, 2))
                            ForwardSim.SAME -> Unit
                        }
                    }
                }
                if (rule.note.isNotBlank()) {
                    Text(
                        rule.note,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
                LastForwardLine(stats)
            }
            // No trash on the card (VS-14): "Delete rule" is in the edit sheet, with Undo.
            Switch(
                checked = rule.enabled,
                onCheckedChange = { actions.onToggle(rule, it) },
                modifier = Modifier.semantics { contentDescription = switchLabel }.testTag("forward_switch_${rule.id}"),
            )
        }
    }
}

/** ID-04: "Last forward 14:02, sent · 12 this week", red when the last one failed. */
@Composable
private fun LastForwardLine(stats: ForwardRuleStats?) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val locale = uiLocale()
    if (stats == null) {
        Text(
            stringResource(R.string.fwdfix_card_never),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
        return
    }
    val time = remember(stats.lastAt, locale) {
        val text = if (DateUtils.isToday(stats.lastAt)) {
            formatClockTime(context, stats.lastAt)
        } else {
            DateUtils.formatDateTime(context, stats.lastAt, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)
        }
        localizeDigits(text, locale)
    }
    val status = stringResource(
        when (stats.lastStatus) {
            ForwardStatus.SENT -> R.string.fwdfix_card_sent
            ForwardStatus.PENDING -> R.string.fwdfix_card_pending
            ForwardStatus.FAILED -> R.string.fwdfix_card_failed
            ForwardStatus.SKIPPED -> R.string.fwdfix_card_skipped
        },
    )
    val last = stringResource(R.string.fwdfix_card_last, time, status)
    val week = pluralStringResource(R.plurals.fwdfix_card_week, stats.week, localizedNumber(stats.week, locale))
    Text(
        stringResource(R.string.rules2_meta, last, week),
        style = MaterialTheme.typography.bodySmall,
        color = if (stats.lastStatus == ForwardStatus.FAILED) colors.error else colors.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

/** The one-time, non-blocking "Forwarded tab added. Hide it?" snackbar; ignoring it keeps the tab. */
@Composable
private fun ForwardedTabHintEffect(added: Boolean, actions: ForwardActions, host: SnackbarHostState?) {
    val text = stringResource(R.string.fwdtab_added)
    val hide = stringResource(R.string.fwdtab_hide)
    LaunchedEffect(added, host) {
        if (!added) return@LaunchedEffect
        if (host == null) {
            actions.onForwardedTabHintDone()
            return@LaunchedEffect
        }
        val result = host.showSnackbar(
            text, actionLabel = hide, withDismissAction = true, duration = SnackbarDuration.Long,
        )
        if (result == SnackbarResult.ActionPerformed) actions.onHideForwardedTab() else actions.onForwardedTabHintDone()
    }
}

/**
 * The dialogs of the Forward tab: the one-time no-lock note and a failed change, and the "Forward rule deleted" Undo
 * snackbar on [snackbarHostState] (null shows none).
 */
@Composable
fun ForwardDialogs(state: ForwardUiState, actions: ForwardActions, snackbarHostState: SnackbarHostState? = null) {
    val deletedText = stringResource(R.string.fwdfix_deleted)
    val undoLabel = stringResource(R.string.spam_undo)
    LaunchedEffect(state.deleted, snackbarHostState) {
        val rule = state.deleted ?: return@LaunchedEffect
        val host = snackbarHostState
        if (host == null) {
            actions.onDeletedShown()
            return@LaunchedEffect
        }
        host.currentSnackbarData?.dismiss()
        val result = host.showSnackbar(
            deletedText,
            actionLabel = undoLabel,
            withDismissAction = true,
            duration = SnackbarDuration.Long,
        )
        if (result == SnackbarResult.ActionPerformed) actions.onUndoDelete(rule) else actions.onDeletedShown()
    }
    ForwardedTabHintEffect(state.forwardedTabAdded, actions, snackbarHostState)
    if (state.prompt == ForwardGatePrompt.NO_LOCK_NOTE) {
        AlertDialog(
            onDismissRequest = actions.onNoLockContinue,
            icon = { Icon(Symbols.Lock, contentDescription = null) },
            title = { Text(stringResource(R.string.forward_no_lock_title)) },
            text = { Text(stringResource(R.string.forward_no_lock_message)) },
            confirmButton = {
                TextButton(onClick = actions.onNoLockContinue) {
                    Text(stringResource(R.string.forward_no_lock_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = actions.onNoLockSetUp) { Text(stringResource(R.string.forward_no_lock_set_up)) }
            },
        )
    }
    if (state.changeFailed) {
        ConfirmDialog(
            message = stringResource(R.string.forward_change_failed),
            confirmLabel = stringResource(R.string.screens_ok),
            onConfirm = actions.onDismissChangeFailed,
            onDismiss = actions.onDismissChangeFailed,
        )
    }
    state.form?.let { ForwardSheet(it, state.sims, actions) }
}

@Composable
private fun conditionHint(type: RuleType): String = stringResource(
    when (type) {
        RuleType.NUMBER -> R.string.rules2_type_number_hint
        RuleType.PREFIX -> R.string.rules2_type_prefix_hint
        RuleType.SENDER_ID -> R.string.spam_type_sender_id_hint
        RuleType.KEYWORD -> R.string.spam_type_keyword_hint
        RuleType.REGEX -> R.string.spam_type_regex_hint
    },
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConditionPicker(
    label: String,
    types: List<RuleType>,
    selected: RuleType?,
    pattern: String,
    valueLabel: String,
    invalid: RuleValidation.Invalid?,
    tag: String,
    onType: (RuleType?) -> Unit,
    onPattern: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.semantics { heading() })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RuleChoiceChip(
                selected = selected == null,
                onClick = { onType(null) },
                label = stringResource(R.string.forward_sheet_any),
            )
            types.forEach { type ->
                RuleChoiceChip(selected = selected == type, onClick = { onType(type) }, label = ruleTypeLabel(type))
            }
        }
        if (selected != null) {
            val shownError = invalid?.takeIf { pattern.isNotEmpty() }
            OutlinedTextField(
                value = pattern,
                onValueChange = onPattern,
                modifier = Modifier.fillMaxWidth().then(rememberKeepInView()).testTag(tag),
                label = { Text(valueLabel) },
                placeholder = { Text(conditionHint(selected)) },
                singleLine = true,
                isError = shownError != null,
                supportingText = shownError?.let { { Text(errorText(it)) } },
                textStyle = MaterialTheme.typography.bodyLarge.let {
                    if (selected == RuleType.REGEX) it.copy(fontFamily = FontFamily.Monospace) else it
                },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = if (selected == RuleType.NUMBER || selected == RuleType.PREFIX) {
                        KeyboardType.Phone
                    } else {
                        KeyboardType.Text
                    },
                ),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ForwardSheet(form: ForwardForm, sims: List<SimInfo>, actions: ForwardActions) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var picking by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    // The target field the contact picker fills in.
    var pickingTarget by rememberSaveable { mutableIntStateOf(0) }
    val pickContact = ActivityResultContracts.StartActivityForResult()
    val contactLauncher = rememberLauncherForActivityResult(pickContact) { result ->
        ForwardContactPicker.numberOf(context, result.data)?.let { actions.onTarget(pickingTarget, it) }
    }
    // The sheet stops below the status bar, and Cancel / Save stay in a bar under the scrolling form.
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * SHEET_HEIGHT_FRACTION
    ModalBottomSheet(onDismissRequest = actions.onDismissForm, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).navigationBarsPadding().imePadding()) {
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    stringResource(
                        if (form.isEdit) R.string.forward_sheet_title_edit else R.string.forward_sheet_title_add,
                    ),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )

                ConditionPicker(
                    label = stringResource(R.string.forward_sheet_sender_label),
                    types = ForwardRule.SENDER_TYPES,
                    selected = form.senderType,
                    pattern = form.senderPattern,
                    valueLabel = stringResource(R.string.forward_sheet_sender_value),
                    invalid = form.senderInvalid,
                    tag = "forward_sender",
                    onType = actions.onSenderType,
                    onPattern = actions.onSenderPattern,
                )
                ConditionPicker(
                    label = stringResource(R.string.forward_sheet_text_label),
                    types = ForwardRule.BODY_TYPES,
                    selected = form.bodyType,
                    pattern = form.bodyPattern,
                    valueLabel = stringResource(R.string.forward_sheet_text_value),
                    invalid = form.bodyInvalid,
                    tag = "forward_body",
                    onType = actions.onBodyType,
                    onPattern = actions.onBodyPattern,
                )
                // A forward rule matches the text as an allow rule does (no see-through of spaced-out letters).
                RulePatternTest(
                    ListKind.ALLOW, form.bodyType, form.bodyPattern,
                    valid = form.bodyType != null && form.bodyPattern.isNotBlank() && form.bodyError == null,
                )
                if (form.noCondition) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    ) {
                        Text(
                            stringResource(R.string.forward_sheet_condition_needed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            stringResource(R.string.forward2_condition_why),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (form.isBroad) {
                    Text(
                        stringResource(R.string.forward2_broad_inline),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }

                Targets(form, actions) { index ->
                    pickingTarget = index
                    context.appLockManager.allowOwnTrip()
                    runCatching { contactLauncher.launch(ForwardContactPicker.intent()) }
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SwitchLine(
                        title = stringResource(R.string.forward_sheet_window),
                        body = stringResource(R.string.forward2_window_help),
                        checked = form.useWindow,
                        onChange = actions.onUseWindow,
                        tag = "forward_window",
                    )
                    if (form.useWindow) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { picking = START }) {
                                Text(stringResource(R.string.forward_sheet_start, formatMinutes(form.windowStart)))
                            }
                            OutlinedButton(onClick = { picking = END }) {
                                Text(stringResource(R.string.forward_sheet_end, formatMinutes(form.windowEnd)))
                            }
                        }
                        // The range in a sentence, e.g. "Forwards every night from 22:00 to 06:00".
                        Text(
                            stringResource(
                                if (form.windowEnd < form.windowStart) {
                                    R.string.forward2_window_summary_night
                                } else {
                                    R.string.forward2_window_summary
                                },
                                formatMinutes(form.windowStart),
                                formatMinutes(form.windowEnd),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (sims.size >= 2) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(R.string.forward_sheet_scope_label),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            RuleChoiceChip(
                                selected = form.subscriptionId == null,
                                onClick = { actions.onScope(null) },
                                label = stringResource(R.string.spam_sim_both),
                            )
                            sims.forEach { sim ->
                                RuleChoiceChip(
                                    selected = form.subscriptionId == sim.subscriptionId,
                                    onClick = { actions.onScope(sim.subscriptionId) },
                                    label = simLabel(sim),
                                )
                            }
                        }
                        Text(
                            stringResource(R.string.forward2_scope_help),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(R.string.forward_sheet_send_from_label),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            RuleChoiceChip(
                                selected = form.sendFrom == ForwardSim.SAME,
                                onClick = { actions.onSendFrom(ForwardSim.SAME) },
                                label = stringResource(R.string.forward_sim_same),
                            )
                            sims.filter { it.slot == 1 || it.slot == 2 }.forEach { sim ->
                                val choice = if (sim.slot == 1) ForwardSim.SIM1 else ForwardSim.SIM2
                                RuleChoiceChip(
                                    selected = form.sendFrom == choice,
                                    onClick = { actions.onSendFrom(choice) },
                                    label = simLabel(sim),
                                )
                            }
                        }
                        Text(
                            stringResource(R.string.forward2_send_help),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                SwitchLine(
                    title = stringResource(R.string.forward2_codes_title),
                    body = stringResource(
                        if (form.includeCodes) R.string.forward2_codes_on else R.string.forward2_codes_off,
                    ),
                    checked = form.includeCodes,
                    onChange = actions.onIncludeCodes,
                    tag = "forward_codes",
                )

                OutlinedTextField(
                    value = form.note,
                    onValueChange = actions.onNote,
                    modifier = Modifier.fillMaxWidth().then(rememberKeepInView()),
                    label = { Text(stringResource(R.string.spam_sheet_note_label)) },
                    singleLine = true,
                )

                if (form.saveFailed) {
                    Text(
                        stringResource(R.string.spam_sheet_save_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                    )
                }

                // VS-14: deleting a rule lives here, not on the card; the list offers Undo afterwards.
                if (form.isEdit) {
                    TextButton(
                        onClick = actions.onDeleteFromForm,
                        enabled = !form.saving,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("forward_delete"),
                    ) {
                        Icon(
                            Symbols.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            stringResource(R.string.fwdfix_delete_rule),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
            RuleSheetBar(
                saveLabel = stringResource(R.string.forward2_save),
                enabled = form.canSubmit,
                hint = when {
                    form.saving -> null
                    form.noCondition -> stringResource(R.string.forward2_hint_condition)
                    form.noTargets -> stringResource(R.string.forward2_hint_target)
                    else -> stringResource(R.string.forward2_hint_fix)
                },
                onCancel = actions.onDismissForm,
                onSave = actions.onSubmit,
                saveTag = "forward_save",
            )
        }
    }
    if (form.confirmingCodes) {
        ConfirmDialog(
            title = stringResource(R.string.forward_codes_title),
            message = stringResource(R.string.forward_codes_message),
            confirmLabel = stringResource(R.string.forward_codes_confirm),
            onConfirm = actions.onConfirmCodes,
            onDismiss = actions.onCancelCodes,
            dismissLabel = stringResource(R.string.spam_cancel),
            destructive = true,
        )
    }
    if (form.test == ForwardTestState.CONFIRMING) {
        val count = form.cleanTargets.size
        ConfirmDialog(
            title = stringResource(R.string.fwdfix_test_title),
            message = pluralStringResource(R.plurals.fwdfix_test_message, count, localizedNumber(count, uiLocale())),
            confirmLabel = stringResource(R.string.fwdfix_test_confirm),
            onConfirm = actions.onConfirmTest,
            onDismiss = actions.onCancelTest,
            dismissLabel = stringResource(R.string.spam_cancel),
        )
    }
    if (form.confirmingBroad) {
        ConfirmDialog(
            title = stringResource(R.string.forward2_broad_title),
            message = stringResource(R.string.forward2_broad_message),
            confirmLabel = stringResource(R.string.forward2_broad_confirm),
            onConfirm = actions.onConfirmBroad,
            onDismiss = actions.onCancelBroad,
            dismissLabel = stringResource(R.string.spam_cancel),
            destructive = true,
        )
    }
    picking?.let { which ->
        TimeDialog(
            title = stringResource(
                if (which == START) R.string.forward_sheet_pick_start else R.string.forward_sheet_pick_end,
            ),
            minutes = if (which == START) form.windowStart else form.windowEnd,
            onPick = {
                if (which == START) actions.onWindowStart(it) else actions.onWindowEnd(it)
                picking = null
            },
            onDismiss = { picking = null },
        )
    }
}

private const val START = "start"
private const val END = "end"

@Composable
private fun simLabel(sim: SimInfo): String =
    if (sim.carrier.isBlank()) {
        stringResource(R.string.spam_sim_label, sim.slot)
    } else {
        stringResource(R.string.spam_sim_named, sim.slot, sim.carrier)
    }

@Composable
private fun Targets(form: ForwardForm, actions: ForwardActions, onPickContact: (Int) -> Unit) {
    val errors = form.targetErrors
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.forward_sheet_targets_label), style = MaterialTheme.typography.labelLarge)
        form.targets.forEachIndexed { index, value ->
            val error = errors.getOrNull(index)
            val valid = error == null && ForwardTargets.clean(value) != null
            OutlinedTextField(
                value = value,
                onValueChange = { actions.onTarget(index, it) },
                modifier = Modifier.fillMaxWidth().then(rememberKeepInView()).testTag("forward_target_$index"),
                label = { Text(stringResource(R.string.forward_sheet_target_n, index + 1)) },
                singleLine = true,
                isError = error != null,
                supportingText = error?.let {
                    {
                        Text(
                            stringResource(
                                when (it) {
                                    TargetError.INVALID -> R.string.forward_err_target_invalid
                                    TargetError.DUPLICATE -> R.string.forward_err_target_duplicate
                                    TargetError.OWN_NUMBER -> R.string.forward2_target_own
                                    TargetError.SAME_AS_SENDER -> R.string.forward2_target_sender
                                },
                            ),
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, autoCorrectEnabled = false),
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // A tick appears once the number reads as a phone number, so it is clear it was understood.
                        if (valid) {
                            Icon(
                                Symbols.Check,
                                contentDescription = stringResource(R.string.forward2_target_ok),
                                tint = colors.primary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        IconButton(onClick = { onPickContact(index) }) {
                            Icon(Symbols.Contacts, contentDescription = stringResource(R.string.forward2_pick_contact))
                        }
                        if (form.targets.size > 1) {
                            IconButton(onClick = { actions.onRemoveTarget(index) }) {
                                val removeLabel = stringResource(R.string.forward_sheet_remove_target, index + 1)
                                Icon(Symbols.Close, contentDescription = removeLabel)
                            }
                        }
                    }
                },
            )
        }
        if (form.canAddTarget) {
            TextButton(onClick = actions.onAddTarget) {
                Icon(Symbols.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.forward_sheet_add_target), modifier = Modifier.padding(start = 8.dp))
            }
        }
        CostNote(form.minSmsPerForward)
        SendTest(form, actions.onAskTest)
    }
}

/**
 * The cost of a forward, right under the numbers: amber, with the least number of SMS one forwarded message takes and
 * what a longer Persian or English text costs (BR-41).
 */
@Composable
private fun CostNote(count: Int) {
    val colors = MaterialTheme.colorScheme
    val locale = uiLocale()
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = colors.tertiaryContainer,
            contentColor = colors.onTertiaryContainer,
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Symbols.Warning, contentDescription = null, modifier = Modifier.size(20.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.forward_sheet_paid), style = MaterialTheme.typography.bodyMedium)
                Text(
                    pluralStringResource(R.plurals.forward2_cost_estimate, count, count),
                    style = MaterialTheme.typography.titleSmall,
                )
                CostExample(R.string.fwdfix_cost_persian, SmsParts.EXAMPLE_PERSIAN, unicode = true, count, locale)
                CostExample(R.string.fwdfix_cost_english, SmsParts.EXAMPLE_ENGLISH, unicode = false, count, locale)
            }
        }
    }
}

@Composable
private fun CostExample(text: Int, length: Int, unicode: Boolean, numbers: Int, locale: java.util.Locale) {
    val parts = SmsParts.count(length, unicode)
    Text(
        stringResource(
            text,
            localizedNumber(length, locale),
            localizedNumber(parts, locale),
            localizedNumber(numbers, locale),
            localizedNumber(parts * numbers, locale),
        ),
        style = MaterialTheme.typography.bodySmall,
    )
}

/** ID-07: one confirmed test SMS to the numbers, with its result under the button. */
@Composable
private fun SendTest(form: ForwardForm, onAsk: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(
            onClick = onAsk,
            enabled = form.canSendTest,
            modifier = Modifier.heightIn(min = 48.dp).testTag("forward_send_test"),
        ) {
            Icon(Symbols.Send, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.fwdfix_test_button), modifier = Modifier.padding(start = 8.dp))
        }
        val result = when (form.test) {
            ForwardTestState.SENDING -> R.string.fwdfix_test_sending
            ForwardTestState.SENT -> R.string.fwdfix_test_sent
            ForwardTestState.FAILED -> R.string.fwdfix_test_failed
            ForwardTestState.IDLE, ForwardTestState.CONFIRMING -> null
        }
        if (result != null) {
            Text(
                stringResource(result),
                style = MaterialTheme.typography.bodyMedium,
                color = if (form.test == ForwardTestState.FAILED) {
                    MaterialTheme.colorScheme.error
                } else {
                    Color.Unspecified
                },
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

/**
 * Bug 11: the focused field, with its error line, is scrolled into view above the keyboard, also when the keyboard
 * opens only after the focus moved (the sheet shrinks after the first bring-into-view). The field grows when its
 * red error line appears while typing (and the Cancel / Save bar gains its hint at the same time), so it is brought
 * into view again then, with a margin below, and the error line is not left behind the bar.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun rememberKeepInView(): Modifier {
    val requester = remember { BringIntoViewRequester() }
    var focused by remember { mutableStateOf(false) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    val margin = with(density) { KEEP_IN_VIEW_MARGIN.toPx() }
    LaunchedEffect(focused, imeBottom, size) {
        if (focused) {
            delay(KEYBOARD_SETTLE_MS)
            runCatching { requester.bringIntoView(Rect(0f, 0f, size.width.toFloat(), size.height + margin)) }
        }
    }
    return remember(requester) {
        Modifier.bringIntoViewRequester(requester)
            .onFocusChanged { focused = it.isFocused }
            .onSizeChanged { size = it }
    }
}

private const val KEYBOARD_SETTLE_MS = 150L
private val KEEP_IN_VIEW_MARGIN = 16.dp

@Composable
private fun SwitchLine(title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit, tag: String) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            modifier = Modifier.semantics { contentDescription = title }.testTag(tag),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(title: String, minutes: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val state = rememberTimePickerState(
        initialHour = minutes / 60,
        initialMinute = minutes % 60,
        is24Hour = DateFormat.is24HourFormat(context),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                TimePicker(state = state)
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(state.hour * 60 + state.minute) }) {
                Text(stringResource(R.string.screens_ok))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.screens_cancel)) } },
    )
}

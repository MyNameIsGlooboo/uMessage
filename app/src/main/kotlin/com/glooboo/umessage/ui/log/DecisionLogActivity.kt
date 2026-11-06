package com.glooboo.umessage.ui.log

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.glooboo.umessage.R
import com.glooboo.umessage.helpers.refreshConversations
import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.spam.DecisionRuleText
import com.glooboo.umessage.spam.ForwardRule
import com.glooboo.umessage.spam.RestoreOutcome
import com.glooboo.umessage.spam.SpamLog
import com.glooboo.umessage.spam.SpamLogEntry
import com.glooboo.umessage.spam.SpamRepository
import com.glooboo.umessage.spam.SpamRule
import com.glooboo.umessage.spam.UMessage
import com.glooboo.umessage.spam.engine.SpamEngine
import com.glooboo.umessage.ui.components.ConfirmDialog
import com.glooboo.umessage.ui.components.EmptyState
import com.glooboo.umessage.ui.components.LoadingState
import com.glooboo.umessage.ui.components.UMessageFilterChip
import com.glooboo.umessage.ui.components.UMessageScaffold
import com.glooboo.umessage.ui.components.UMessageTopBar
import com.glooboo.umessage.ui.components.displayNumber
import com.glooboo.umessage.ui.components.isolateNumbers
import com.glooboo.umessage.ui.components.localizedNumber
import com.glooboo.umessage.ui.components.uiLocale
import com.glooboo.umessage.ui.icons.Symbols
import com.glooboo.umessage.ui.settings.SimCard
import com.glooboo.umessage.ui.settings.SimCards
import com.glooboo.umessage.ui.spam.SenderBlocking
import com.glooboo.umessage.ui.spam.SpamRepositoryProvider
import com.glooboo.umessage.ui.theme.UMessageTheme
import com.glooboo.umessage.ui.theme.applyUMessageWindow
import com.glooboo.umessage.ui.theme.success
import com.glooboo.umessage.ui.thread.planSpamReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Last filter decisions (sender, SIM, decision, reason). Message text is never logged. */
class DecisionLogActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        applyUMessageWindow()
        super.onCreate(savedInstanceState)
        setContent { UMessageTheme { DecisionLogRoute(onBack = ::finish) } }
    }
}

/** Everything the rows need besides the entries: rule names, forward rules and the SIMs in the phone. */
internal data class LogContext(
    val rules: List<SpamRule> = emptyList(),
    val forwardRules: List<ForwardRule> = emptyList(),
    val sims: List<SimCard> = emptyList(),
)

@Composable
private fun DecisionLogRoute(onBack: () -> Unit) {
    val context = LocalContext.current
    val log = remember { UMessage.log(context) }
    val repository = remember { SpamRepositoryProvider.get(context) }
    val scope = rememberCoroutineScope()
    // The first read loads the log file, so it runs on IO instead of in composition.
    var entries by remember { mutableStateOf<List<SpamLogEntry>?>(null) }
    var forwardRules by remember { mutableStateOf<List<ForwardRule>>(emptyList()) }
    var sims by remember { mutableStateOf<List<SimCard>>(emptyList()) }
    val rules by repository.rules.collectAsStateWithLifecycle(initialValue = emptyList())
    val blocked by repository.blocked.collectAsStateWithLifecycle(initialValue = emptyList())
    LaunchedEffect(log) {
        withContext(Dispatchers.IO) {
            // Forward entries name their rule by its note or condition; B4: SIM slots need the SIM list.
            forwardRules = runCatching { SpamEngine.forwardRepository(context).currentRules() }
                .getOrDefault(emptyList())
            sims = runCatching { SimCards.load(context) }.getOrDefault(emptyList())
            entries = log.entries()
        }
    }
    val snackbar = remember { SnackbarHostState() }
    val actions = remember(log, repository) {
        LogActions(context, log, repository, snackbar) { entries = withContext(Dispatchers.IO) { log.entries() } }
    }
    DecisionLogScreen(
        entries = entries,
        logContext = LogContext(rules, forwardRules, sims),
        blocked = blocked,
        snackbarHostState = snackbar,
        onBack = onBack,
        onFix = { entry, allow -> scope.launch { actions.fix(entry, allow) } },
        onRestore = { entry, message -> scope.launch { actions.restore(entry, message) } },
        onShare = { shown -> scope.launch { actions.share(shown, sims) } },
        onClear = {
            scope.launch {
                entries = withContext(Dispatchers.IO) {
                    log.clear()
                    log.entries()
                }
            }
        },
    )
}

/** The writes a log row can start, each followed by a snackbar; fixes offer Undo and are stored in the log (B5). */
private class LogActions(
    private val context: Context,
    private val log: SpamLog,
    private val repository: SpamRepository,
    private val snackbar: SnackbarHostState,
    private val reload: suspend () -> Unit,
) {
    suspend fun fix(entry: SpamLogEntry, allow: Boolean) {
        snackbar.currentSnackbarData?.dismiss()
        val failedText = context.getString(R.string.w3b_fix_failed)
        // The same calls the Blocked tab and the thread menu use; nothing is written until the user picks.
        val sender = entry.sender.trim()
        var undo: (suspend () -> Unit)? = null
        val ok = runCatching {
            if (allow) {
                val rules = repository.rules.first()
                val already = withContext(Dispatchers.Default) {
                    planSpamReport(rules, sender).allowRulesToRemove.isNotEmpty()
                }
                if (!already) {
                    val change = SenderBlocking.alwaysAllow(repository, listOf(sender))
                    undo = { SenderBlocking.undoAllow(repository, change) }
                }
            } else {
                val change = SenderBlocking.block(repository, listOf(sender))
                if (!change.isEmpty) undo = { SenderBlocking.undo(repository, change) }
            }
        }.isSuccess
        if (!ok) {
            snackbar.showSnackbar(failedText)
            return
        }
        mark(entry, if (allow) SpamLog.FIX_ALLOWED else SpamLog.FIX_BLOCKED)
        val message = context.getString(
            if (allow) R.string.w3b_fix_allowed else R.string.w3b_fix_blocked,
            isolateNumbers(displayNumber(context, sender)),
        )
        val revert = undo
        if (revert == null) {
            snackbar.showSnackbar(message)
            return
        }
        val result = snackbar.showSnackbar(
            message, actionLabel = context.getString(R.string.spam_undo), duration = SnackbarDuration.Long,
        )
        if (result == SnackbarResult.ActionPerformed) {
            runCatching { revert() }.onFailure { snackbar.showSnackbar(failedText) }
            mark(entry, "")
        }
    }

    /** Puts the blocked message back in the inbox (the Blocked tab's "Not spam"); rules are not touched. */
    suspend fun restore(entry: SpamLogEntry, message: BlockedMessage) {
        snackbar.currentSnackbarData?.dismiss()
        val outcome = runCatching { repository.markNotSpam(message.id) }.getOrDefault(RestoreOutcome.FAILED)
        val text = when (outcome) {
            RestoreOutcome.RESTORED -> R.string.declog_restore_done
            RestoreOutcome.TEXT_ONLY -> R.string.declog_restore_text_only
            RestoreOutcome.NOTHING -> R.string.declog_restore_nothing
            RestoreOutcome.FAILED, RestoreOutcome.NOT_FOUND -> R.string.declog_restore_failed
        }
        if (outcome != RestoreOutcome.FAILED && outcome != RestoreOutcome.NOT_FOUND) {
            mark(entry, SpamLog.FIX_RESTORED)
            refreshConversations()
        }
        snackbar.showSnackbar(context.getString(text))
    }

    /** ID-39: the shown decisions as a CSV file through the share sheet. */
    suspend fun share(shown: List<SpamLogEntry>, sims: List<SimCard>) {
        val ok = runCatching {
            val csv = withContext(Dispatchers.Default) {
                DecisionLogCsv.build(shown, { simSlot(it.subscriptionId, sims) })
            }
            DecisionLogCsv.share(context, csv, shown.size)
        }.isSuccess
        if (!ok) snackbar.showSnackbar(context.getString(R.string.declog_share_failed))
    }

    private suspend fun mark(entry: SpamLogEntry, fix: String) {
        withContext(Dispatchers.IO) { log.markFixed(entry.time, entry.sender, fix) }
        reload()
    }
}

/**
 * Stateless log screen: large collapsing top bar, the All / Blocked / Allowed filter, one header per day and one M3
 * list item per decision (repeats collapsed). Share and Clear are in the overflow menu.
 */
@Composable
private fun DecisionLogScreen(
    entries: List<SpamLogEntry>?,
    onBack: () -> Unit,
    onClear: () -> Unit,
    logContext: LogContext = LogContext(),
    blocked: List<BlockedMessage> = emptyList(),
    snackbarHostState: SnackbarHostState? = null,
    onFix: (SpamLogEntry, Boolean) -> Unit = { _, _ -> },
    onRestore: (SpamLogEntry, BlockedMessage) -> Unit = { _, _ -> },
    onShare: (List<SpamLogEntry>) -> Unit = {},
) {
    var filter by rememberSaveable { mutableStateOf(LogFilter.ALL) }
    var detailsKey by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val all = entries.orEmpty()
    val days = remember(all, filter) { groupLog(all, filter) }
    val shown = remember(all, filter) { all.filter { filter.accepts(it) } }
    UMessageScaffold(
        topBar = {
            UMessageTopBar(title = stringResource(R.string.declog_title), onBack = onBack) {
                if (all.isNotEmpty()) {
                    LogMenu(shownCount = shown.size, onShare = { onShare(shown) }, onClear = { confirmClear = true })
                }
            }
        },
        snackbarHostState = snackbarHostState,
    ) { padding ->
        val top = Modifier.padding(top = padding.calculateTopPadding())
        when {
            entries == null -> LoadingState(top)
            all.isEmpty() -> Column(Modifier.fillMaxSize().then(top)) {
                EmptyState(
                    iconRes = R.drawable.ic_spam_shield_check,
                    title = stringResource(R.string.declog_empty_title),
                    body = stringResource(R.string.declog_empty_body),
                )
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().then(top),
                contentPadding = PaddingValues(bottom = 8.dp + padding.calculateBottomPadding()),
            ) {
                item(key = "retention") { RetentionNote() }
                item(key = "filter") { FilterRow(filter) { filter = it } }
                if (days.isEmpty()) item(key = "no-match") { NoMatch() }
                days.forEach { day ->
                    item(key = "day-${day.day}") { DayHeader(day) }
                    // The log has no ids: the position keeps keys unique even for two entries in the same millisecond.
                    itemsIndexed(day.rows, key = { index, group -> "${day.day}#$index#${group.key}" }) { _, group ->
                        LogRow(group, logContext, onClick = { detailsKey = group.key })
                    }
                }
            }
        }
    }
    if (confirmClear) {
        ConfirmDialog(
            title = stringResource(R.string.declog_ui_clear_title),
            message = stringResource(R.string.declog_ui_clear_message),
            confirmLabel = stringResource(R.string.declog_ui_clear_action),
            destructive = true,
            onConfirm = { confirmClear = false; onClear() },
            onDismiss = { confirmClear = false },
        )
    }
    val target = detailsKey?.let { key -> days.firstNotNullOfOrNull { d -> d.rows.firstOrNull { it.key == key } } }
    if (target != null) {
        DecisionDetailsSheet(
            group = target,
            text = rememberRowText(target, logContext),
            blockedMessage = remember(target, blocked) { matchBlocked(target.newest, blocked) },
            onDismiss = { detailsKey = null },
            actions = DecisionSheetActions(
                onFix = { allow -> detailsKey = null; onFix(target.newest, allow) },
                onRestore = { message -> detailsKey = null; onRestore(target.newest, message) },
            ),
        )
    }
}

@Composable
private fun LogMenu(shownCount: Int, onShare: () -> Unit, onClear: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Symbols.MoreVert, contentDescription = stringResource(R.string.spam_more_options))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (shownCount > 0) {
                DropdownMenuItem(
                    text = { Text(pluralStringResource(R.plurals.declog_share_count, shownCount, shownCount)) },
                    leadingIcon = { Icon(Symbols.Upload, contentDescription = null) },
                    onClick = { open = false; onShare() },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.declog_clear)) },
                leadingIcon = { Icon(Symbols.Delete, contentDescription = null) },
                onClick = { open = false; onClear() },
            )
        }
    }
}

/** States what the log keeps and for how long (SU-40). */
@Composable
private fun RetentionNote() {
    Text(
        text = pluralStringResource(R.plurals.declog_kept_for, SpamLog.RETENTION_DAYS, SpamLog.RETENTION_DAYS),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun FilterRow(selected: LogFilter, onSelect: (LogFilter) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LogFilter.entries.forEach { option ->
            val label = when (option) {
                LogFilter.ALL -> R.string.declog_filter_all
                LogFilter.BLOCKED -> R.string.declog_filter_blocked
                LogFilter.ALLOWED -> R.string.declog_filter_allowed
            }
            UMessageFilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = stringResource(label),
            )
        }
    }
}

@Composable
private fun NoMatch() {
    Text(
        stringResource(R.string.declog_filter_empty),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
    )
}

/** SE-37: "Today", "Yesterday" or the date above that day's rows. */
@Composable
private fun DayHeader(day: LogDay) {
    Text(
        rememberLogDay(day.day),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp).semantics { heading() },
    )
}

/** A row's words, resolved once and shared with the details sheet. */
internal data class RowText(
    val decision: String,
    /** The reason, naming the rule when one decided (SE-39); for forwards the rule's name. */
    val reason: String?,
    val source: String,
    /** "SIM 2", or null on a single-SIM phone (B4). */
    val sim: String?,
    /** "Fixed" or "Restored", or null. */
    val mark: String?,
    /** "3 times", or null for a single decision (SE-41). */
    val count: String?,
)

@Composable
internal fun rememberRowText(group: LogRowGroup, logContext: LogContext): RowText {
    val entry = group.newest
    val resources = LocalResources.current
    val forward = ForwardLogText.isForward(entry.decision)
    val slot = simSlot(entry.subscriptionId, logContext.sims)
    val locale = uiLocale()
    val context = LocalContext.current
    return RowText(
        decision = if (forward) {
            ForwardLogText.sentence(resources, entry.reason)
        } else {
            stringResource(decisionLabel(entry.decision))
        },
        reason = if (forward) {
            ForwardLogText.ruleLabel(resources, entry.reason, logContext.forwardRules)
        } else {
            DecisionRuleText.byRule(resources, entry, logContext.rules) { isolateNumbers(displayNumber(context, it)) }
                ?: reasonLabel(entry.reason)
        },
        source = stringResource(sourceLabel(entry.source)),
        sim = slot?.let { unbreakable(stringResource(R.string.declog_sim, localizedNumber(it, locale))) },
        mark = when (group.fix) {
            "" -> null
            SpamLog.FIX_RESTORED -> stringResource(R.string.declog_restored)
            else -> stringResource(R.string.w3b_fix_done)
        },
        count = if (group.count > 1) {
            pluralStringResource(R.plurals.declog_times, group.count, localizedNumber(group.count, locale))
        } else {
            null
        },
    )
}

/**
 * One decision (or a run of the same one): leading status icon, sender as headline, "decision, count, reason,
 * source, SIM, Fixed" as supporting text, clock time as trailing text. Blocked and Error use the error role;
 * Allowed uses the success colour (see [decisionColor]).
 */
@Composable
private fun LogRow(group: LogRowGroup, logContext: LogContext, onClick: () -> Unit) {
    val entry = group.newest
    val text = rememberRowText(group, logContext)
    val tint = decisionColor(entry.decision)
    val time = rememberLogTime(entry.time)
    val details = listOfNotNull(text.count, text.reason, text.source, text.sim, text.mark)
    val separator = stringResource(R.string.declog_separator)
    val sender = senderText(entry.sender)
    // Meaning never depends on colour alone: each decision also has its own icon and word.
    val supporting = buildAnnotatedString {
        withStyle(SpanStyle(color = tint, fontWeight = FontWeight.Medium)) { append(text.decision) }
        details.forEach { append(separator); append(it) }
    }
    ListItem(
        modifier = Modifier
            .clickable(onClickLabel = stringResource(R.string.declog_open_details, sender), onClick = onClick)
            .semantics(mergeDescendants = true) {},
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { Icon(decisionIcon(entry.decision), contentDescription = null, tint = tint) },
        headlineContent = {
            Text(sender, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = { Text(supporting, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        trailingContent = {
            // One line, so the time column never takes width from the detail text beside it.
            Text(
                unbreakable(time),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
            )
        },
    )
}

/**
 * Blocked and Error map to the M3 error role. Allowed uses the app's fixed success green, not primary: with a red
 * primary (the Coral preset) primary looked like the error red of Blocked (N5). The icon and the word carry the
 * meaning, so the colour is only a second cue.
 */
@Composable
internal fun decisionColor(decision: String): Color = when (decisionTone(decision)) {
    DecisionTone.NEGATIVE -> MaterialTheme.colorScheme.error
    DecisionTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    DecisionTone.POSITIVE -> MaterialTheme.colorScheme.success
}

internal enum class DecisionTone { POSITIVE, NEGATIVE, NEUTRAL }

/** Which colour family a decision is shown in; never primary, which can be red in some presets. */
internal fun decisionTone(decision: String): DecisionTone = when (decision) {
    "BLOCK", "ERROR", "FORWARD_FAILED" -> DecisionTone.NEGATIVE
    "FORWARD_SKIPPED" -> DecisionTone.NEUTRAL
    else -> DecisionTone.POSITIVE
}

/** N8: the sender as the rest of the app shows it: a bare number grouped, in the app's digits, kept left-to-right. */
@Composable
internal fun senderText(sender: String): String =
    if (sender.isBlank()) "?" else isolateNumbers(displayNumber(sender))

private fun decisionIcon(decision: String): ImageVector = when (decision) {
    "ERROR", "FORWARD_FAILED" -> Symbols.Error
    "BLOCK" -> Symbols.Block
    "FORWARD", "FORWARD_SKIPPED" -> Symbols.Forward
    else -> Symbols.CheckCircleFilled
}

/** Engine tokens to translatable labels. */
internal fun decisionLabel(decision: String): Int = when (decision) {
    "BLOCK" -> R.string.ui_log_decision_blocked
    "ERROR" -> R.string.ui_log_decision_error
    "FORWARD" -> R.string.ui_log_decision_forwarded
    "FORWARD_FAILED" -> R.string.ui_log_decision_forward_failed
    "FORWARD_SKIPPED" -> R.string.ui_log_decision_forward_skipped
    else -> R.string.ui_log_decision_allowed
}

@Composable
private fun reasonLabel(reason: String): String = when (reason) {
    "ALLOW_RULE" -> stringResource(R.string.ui_log_reason_allow_rule)
    "BLOCK_RULE" -> stringResource(R.string.ui_log_reason_block_rule)
    "CONTACT" -> stringResource(R.string.ui_log_reason_contact)
    "OTP_EXEMPTION" -> stringResource(R.string.ui_log_reason_otp)
    "UNKNOWN_SENDER" -> stringResource(R.string.ui_log_reason_unknown_sender)
    "MODE_OFF" -> stringResource(R.string.ui_log_reason_mode_off)
    "NO_MATCH" -> stringResource(R.string.ui_log_reason_no_match)
    "CONTACTS_UNAVAILABLE" -> stringResource(R.string.ui_log_reason_contacts_unavailable)
    "KNOWN_CONVERSATION" -> stringResource(R.string.ui_log_reason_known_conversation)
    "FOREIGN_NUMBER" -> stringResource(R.string.region_reason_foreign)
    // Errors log the exception class name; it is only useful as-is.
    else -> reason
}

internal fun sourceLabel(source: String): Int = when (source) {
    "MMS", "MMS_PUSH", "MMS_CONTENT" -> R.string.ui_log_source_mms
    "PENDING_REPLAY" -> R.string.ui_log_source_retry
    "FORWARD" -> R.string.ui_log_source_forward
    else -> R.string.ui_log_source_sms
}

// region Previews

private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 60 * MINUTE_MS
private const val DAY_MS = 24 * HOUR_MS

private fun previewEntries(now: Long = System.currentTimeMillis()) = listOf(
    SpamLogEntry(now - MINUTE_MS, "+14155550133", 1, "BLOCK", "UNKNOWN_SENDER", "SMS"),
    SpamLogEntry(now - 2 * MINUTE_MS, "+14155550133", 1, "BLOCK", "UNKNOWN_SENDER", "SMS"),
    SpamLogEntry(now - HOUR_MS / 2, "VM-HDFCBK", 2, "ALLOW", "OTP_EXEMPTION", "SMS"),
    SpamLogEntry(
        now - HOUR_MS, "+14155550100", 1, "ALLOW", "ALLOW_RULE", "MMS_PUSH", 1, "+1415555", SpamLog.FIX_ALLOWED,
    ),
    SpamLogEntry(now - 2 * HOUR_MS, "AX-PROMOS", -1, "BLOCK", "BLOCK_RULE", "SMS", 2, "lottery"),
    SpamLogEntry(now - DAY_MS, "+919876543210", 1, "ERROR", "IllegalStateException", "PENDING_REPLAY"),
)

private val previewSims = listOf(SimCard(1, 1, "Carrier", null, true), SimCard(2, 2, "Other", null, false))

@Composable
private fun LogPreviewHost(entries: List<SpamLogEntry>?, rtl: Boolean = false) = UMessageTheme {
    val direction = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
    CompositionLocalProvider(LocalLayoutDirection provides direction) {
        DecisionLogScreen(entries = entries, onBack = {}, onClear = {}, logContext = LogContext(sims = previewSims))
    }
}

@Preview(name = "Decision log", showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun DecisionLogPreview() = LogPreviewHost(previewEntries())

@Preview(
    name = "Decision log - dark", showBackground = true, widthDp = 360, heightDp = 720,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun DecisionLogDarkPreview() = LogPreviewHost(previewEntries())

@Preview(name = "Decision log - RTL", showBackground = true, widthDp = 360, heightDp = 720, locale = "fa")
@Composable
private fun DecisionLogRtlPreview() = LogPreviewHost(previewEntries(), rtl = true)

@Preview(name = "Decision log - large font", showBackground = true, widthDp = 360, heightDp = 720, fontScale = 1.3f)
@Composable
private fun DecisionLogLargeFontPreview() = LogPreviewHost(previewEntries())

@Preview(name = "Decision log - empty", showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun DecisionLogEmptyPreview() = LogPreviewHost(emptyList())

// endregion

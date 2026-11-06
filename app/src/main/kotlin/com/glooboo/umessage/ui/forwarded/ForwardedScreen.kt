package com.glooboo.umessage.ui.forwarded

import android.app.Activity
import android.content.Intent
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.glooboo.umessage.R
import com.glooboo.umessage.spam.ForwardLogEntry
import com.glooboo.umessage.spam.ForwardRule
import com.glooboo.umessage.spam.ForwardStatus
import com.glooboo.umessage.spam.ForwardLogPolicy
import com.glooboo.umessage.spam.data.ForwardResend
import com.glooboo.umessage.spam.data.ForwardLogRecorder
import com.glooboo.umessage.spam.engine.ForwardRateLimit
import com.glooboo.umessage.ui.components.ConfirmDialog
import com.glooboo.umessage.ui.components.EmptyState
import com.glooboo.umessage.ui.components.InfoChip
import com.glooboo.umessage.ui.components.InfoDialog
import com.glooboo.umessage.ui.components.LoadingState
import com.glooboo.umessage.ui.components.SectionHeader
import com.glooboo.umessage.ui.components.UMessageBottomSheet
import com.glooboo.umessage.ui.components.UMessageScaffold
import com.glooboo.umessage.ui.components.UMessageTopBar
import com.glooboo.umessage.ui.components.displayNumber
import com.glooboo.umessage.ui.components.formatClockTime
import com.glooboo.umessage.ui.components.isolateNumbers
import com.glooboo.umessage.ui.components.localizeDigits
import com.glooboo.umessage.ui.components.localizedNumber
import com.glooboo.umessage.ui.components.uiLocale
import com.glooboo.umessage.ui.icons.Symbols
import com.glooboo.umessage.ui.lock.LockActivity
import com.glooboo.umessage.ui.theme.contentDirection
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The Forwarded tab: a plain-language log of what was forwarded (status, SIM, the rule that matched), the pause-all
 * switch, retry for failed forwards, and an empty state. It shows no message text except in the details sheet, where
 * the original is read live from the saved messages.
 */
@Composable
fun ForwardedRoute(
    onManageRules: () -> Unit,
    onOpenThread: (threadId: Long, title: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ForwardedViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val resources = LocalResources.current

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            if (event is ForwardedEvent.Retried) {
                val locale = resources.configuration.locales[0]
                val parts = buildList {
                    if (event.sent > 0) {
                        add(resources.getQuantityString(R.plurals.nav_fwd_retry_sent, event.sent, event.sent))
                    }
                    // Each reason named on its own, as the "Send again?" dialog does.
                    listOf(
                        R.plurals.fwdfix_retry_not_sent_paused to event.paused,
                        R.plurals.fwdfix_retry_not_sent_rule to event.ruleOff,
                        R.plurals.fwdfix_retry_not_sent_limit to event.limited,
                    ).filter { it.second > 0 }
                        .forEach { (id, n) -> add(resources.getQuantityString(id, n, localizedNumber(n, locale))) }
                    if (event.failed > 0) add(resources.getString(R.string.nav_fwd_retry_failed))
                    if (event.missing > 0) add(resources.getString(R.string.nav_fwd_retry_missing))
                }
                snackbar.currentSnackbarData?.dismiss()
                if (parts.isNotEmpty()) snackbar.showSnackbar(parts.joinToString(" "))
            }
        }
    }
    // Failed forwards count as seen while the tab is open and again when it closes.
    LifecycleResumeEffect(viewModel) {
        viewModel.markSeen()
        onPauseOrDispose { viewModel.markSeen() }
    }

    // Resuming forwarding asks for the app lock like turning a rule on does; pausing never asks.
    val unlockToResume = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) viewModel.setPaused(false)
    }
    ForwardedScreen(
        state = state,
        snackbarHostState = snackbar,
        onPausedChange = { paused ->
            if (viewModel.pauseNeedsUnlock(paused)) {
                val confirm = Intent(context, LockActivity::class.java).putExtra(LockActivity.EXTRA_CONFIRM, true)
                unlockToResume.launch(confirm)
            } else {
                viewModel.setPaused(paused)
            }
        },
        onRetry = { ids -> viewModel.retry(ids) },
        onRetryAll = viewModel::retryAll,
        onManageRules = onManageRules,
        onOpenThread = { entry -> viewModel.openThread(entry, onOpenThread) },
        modifier = modifier,
    )
}

@Composable
fun ForwardedScreen(
    state: ForwardedUiState,
    snackbarHostState: SnackbarHostState,
    onPausedChange: (Boolean) -> Unit,
    onRetry: (List<Long>) -> Unit,
    onRetryAll: () -> Unit,
    onManageRules: () -> Unit,
    onOpenThread: (ForwardLogEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    var openId by rememberSaveable { mutableStateOf<Long?>(null) }
    var confirmRetryAll by rememberSaveable { mutableStateOf(false) }
    val rules = remember(state.rules) { state.rules.associateBy { it.id } }
    val groups = remember(state.entries) { groupByDay(state.entries) }
    val failedCount = state.failed.size
    // "Retry all" covers the last day's failures only (M2); older ones keep their own Retry.
    val retryAllCount = remember(state.entries) { state.retryAllIds(System.currentTimeMillis()).size }

    UMessageScaffold(
        modifier = modifier,
        snackbarHostState = snackbarHostState,
        topBar = { UMessageTopBar(title = stringResource(R.string.main_tab_forwarded), onBack = null) },
    ) { padding ->
        when {
            state.loading -> LoadingState(Modifier.padding(top = padding.calculateTopPadding()))
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = padding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding() + 8.dp,
                ),
            ) {
                item(key = "status") { StatusCard(state, onPausedChange, onManageRules) }
                if (state.showPaidNote) {
                    item(key = "paid") {
                        Text(
                            stringResource(R.string.nav_fwd_paid_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                }
                if (failedCount > 0) {
                    item(key = "retry-all") {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                pluralStringResource(
                                    R.plurals.nav_fwd_failed_count,
                                    failedCount,
                                    localizedNumber(failedCount, uiLocale()),
                                ),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f),
                            )
                            if (retryAllCount > 0) {
                                TextButton(
                                    onClick = { confirmRetryAll = true },
                                    enabled = !state.busy,
                                    modifier = Modifier.heightIn(min = 48.dp),
                                ) {
                                    Text(
                                        stringResource(
                                            R.string.nav_fwd_retry_all,
                                            localizedNumber(retryAllCount, uiLocale()),
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
                if (state.entries.isEmpty()) {
                    item(key = "empty") {
                        EmptyState(
                            icon = Symbols.Forward,
                            title = stringResource(R.string.nav_fwd_empty_title),
                            body = stringResource(R.string.nav_fwd_empty_body),
                            modifier = Modifier.padding(top = 24.dp),
                            action = {
                                OutlinedButton(onClick = onManageRules) {
                                    Text(stringResource(R.string.nav_fwd_manage_rules))
                                }
                            },
                        )
                    }
                }
                groups.forEach { group ->
                    item(key = "day-${group.day}") { SectionHeader(dayLabel(group.day)) }
                    items(group.entries, key = { it.id }, contentType = { "entry" }) { entry ->
                        EntryRow(
                            entry = entry,
                            rule = rules[entry.ruleId],
                            state = state,
                            busy = state.busy,
                            onClick = { openId = entry.id },
                            onRetry = { onRetry(listOf(entry.id)) },
                        )
                        HorizontalDivider(
                            Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                }
            }
        }
    }

    val open = openId?.let { id -> state.entries.firstOrNull { it.id == id } }
    if (open != null) {
        DetailsSheet(
            entry = open,
            rule = rules[open.ruleId],
            state = state,
            onDismiss = { openId = null },
            onRetry = {
                openId = null
                onRetry(listOf(open.id))
            },
            onManageRules = {
                openId = null
                onManageRules()
            },
            onOpenThread = {
                openId = null
                onOpenThread(open)
            },
        )
    }

    if (confirmRetryAll) RetryAllDialog(state, onRetryAll, onDismiss = { confirmRetryAll = false })
}

/**
 * "Send again?": counts only the forwards that will really go out now (M2) and says why the others will not. When
 * none would go out, it only explains.
 */
@Composable
private fun RetryAllDialog(state: ForwardedUiState, onRetryAll: () -> Unit, onDismiss: () -> Unit) {
    val locale = uiLocale()
    val preview = remember(state) {
        val now = System.currentTimeMillis()
        state.retryPreview(state.retryAllIds(now), now)
    }
    val reasons = listOf(
        R.plurals.fwdfix_retry_blocked_paused to preview.paused,
        R.plurals.fwdfix_retry_blocked_rule to preview.ruleOff,
        R.plurals.fwdfix_retry_blocked_limit to preview.limited,
    ).filter { it.second > 0 }.map { (id, n) -> pluralStringResource(id, n, localizedNumber(n, locale)) }
    if (preview.willSend == 0) {
        InfoDialog(onDismiss = onDismiss, title = stringResource(R.string.fwdfix_retry_none_title)) {
            Text(reasons.joinToString("\n"), style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    val count = pluralStringResource(
        R.plurals.nav_fwd_retry_all_message, preview.willSend, localizedNumber(preview.willSend, locale),
    )
    ConfirmDialog(
        title = stringResource(R.string.nav_fwd_retry_all_title),
        message = (listOf(count) + reasons + stringResource(R.string.fwdfix_retry_all_recent)).joinToString("\n"),
        confirmLabel = stringResource(R.string.nav_fwd_retry_confirm),
        onConfirm = {
            onDismiss()
            onRetryAll()
        },
        onDismiss = onDismiss,
    )
}

@Composable
private fun ruleLine(rule: ForwardRule?): String {
    if (rule == null) return stringResource(R.string.nav_fwd_rule_deleted)
    val sender = if (rule.senderType != null) {
        stringResource(R.string.nav_fwd_rule_sender, isolateNumbers(rule.senderPattern))
    } else {
        stringResource(R.string.nav_fwd_rule_any_sender)
    }
    val text = if (rule.bodyType != null) {
        stringResource(R.string.nav_fwd_rule_text, rule.bodyPattern)
    } else {
        stringResource(R.string.nav_fwd_rule_any_text)
    }
    return stringResource(R.string.nav_fwd_rule_line, sender, text)
}

@Composable
private fun EntryRow(
    entry: ForwardLogEntry,
    rule: ForwardRule?,
    state: ForwardedUiState,
    busy: Boolean,
    onClick: () -> Unit,
    onRetry: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val locale = uiLocale()
    val time = remember(entry.at, locale) { localizeDigits(formatClockTime(context, entry.at), locale) }
    val slot = simSlotOf(state.sims, entry.subscriptionId)
    val openLabel = stringResource(R.string.nav_fwd_open_details)
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = openLabel, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                isolateNumbers(displayNumber(state.nameOf(entry.sourceAddress))),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(time, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, maxLines = 1)
        }
        Text(
            stringResource(R.string.nav_fwd_to, isolateNumbers(displayNumber(state.nameOf(entry.target)))),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusChip(entry.status)
            if (slot != null) InfoChip(stringResource(R.string.nav_fwd_sim, slot))
        }
        Text(
            ruleLine(rule),
            style = MaterialTheme.typography.bodySmall.contentDirection(),
            color = colors.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (entry.status == ForwardStatus.FAILED) {
            TextButton(onClick = onRetry, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.nav_fwd_retry))
            }
        }
    }
}

@Composable
private fun DetailsSheet(
    entry: ForwardLogEntry,
    rule: ForwardRule?,
    state: ForwardedUiState,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onManageRules: () -> Unit,
    onOpenThread: () -> Unit,
) {
    val context = LocalContext.current
    val locale = uiLocale()
    val slot = simSlotOf(state.sims, entry.subscriptionId)
    val whenText = remember(entry.at, locale) {
        localizeDigits(
            DateUtils.formatDateTime(
                context,
                entry.at,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
            ),
            locale,
        )
    }
    // The original is read when the sheet opens and never kept; null while loading, then the text or "" when gone.
    val original by produceState<String?>(initialValue = null, entry.id) {
        value = withContext(Dispatchers.IO) {
            ForwardResend.originalBody(context, entry.sourceAddress, entry.sourceDate, entry.sourceIsMms).orEmpty()
        }
    }
    UMessageBottomSheet(onDismiss = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.nav_fwd_details_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                StatusChip(entry.status)
            }
            DetailLine(
                stringResource(R.string.nav_fwd_detail_from),
                isolateNumbers(displayNumber(state.nameOf(entry.sourceAddress))),
            )
            DetailLine(
                stringResource(R.string.nav_fwd_detail_to),
                isolateNumbers(displayNumber(state.nameOf(entry.target))),
            )
            DetailLine(stringResource(R.string.nav_fwd_detail_when), whenText)
            if (slot != null) {
                DetailLine(stringResource(R.string.nav_fwd_detail_sim), stringResource(R.string.nav_fwd_sim, slot))
            }
            DetailLine(stringResource(R.string.nav_fwd_detail_rule), ruleLine(rule))
            when (entry.status) {
                ForwardStatus.FAILED -> Text(
                    stringResource(
                        if (entry.errorCode == ForwardLogRecorder.RADIO_FAILED) {
                            R.string.nav_fwd_error_radio
                        } else {
                            R.string.nav_fwd_error_refused
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                ForwardStatus.SKIPPED -> Text(
                    if (entry.errorCode == ForwardLogPolicy.SKIP_RATE_LIMITED) {
                        stringResource(
                            R.string.fwdfix_error_rate_limited,
                            localizedNumber(ForwardRateLimit.MAX_PER_RULE_PER_HOUR, locale),
                        )
                    } else {
                        stringResource(R.string.fwdfix_error_daily_limit)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                else -> Unit
            }
            DetailLine(
                stringResource(R.string.nav_fwd_detail_message),
                when (val text = original) {
                    null -> stringResource(R.string.nav_fwd_message_loading)
                    "" -> stringResource(R.string.nav_fwd_message_gone)
                    else -> text.orEmpty()
                },
                direction = true,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (entry.status == ForwardStatus.FAILED) {
                    Button(onClick = onRetry, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.nav_fwd_retry))
                    }
                }
                OutlinedButton(onClick = onOpenThread, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.nav_fwd_open_chat))
                }
                TextButton(onClick = onManageRules, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.nav_fwd_manage_rules))
                }
            }
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String, direction: Boolean = false) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium.let { if (direction) it.contentDirection() else it },
        )
    }
}

@Composable
private fun dayLabel(day: LocalDate): String {
    val context = LocalContext.current
    val resources = LocalResources.current
    val locale = uiLocale()
    val today = LocalDate.now()
    return remember(day, today, locale) {
        when (day) {
            today -> resources.getString(R.string.nav_fwd_today)
            today.minusDays(1) -> resources.getString(R.string.nav_fwd_yesterday)
            else -> localizeDigits(
                DateUtils.formatDateTime(
                    context,
                    day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_ALL,
                ),
                locale,
            )
        }
    }
}

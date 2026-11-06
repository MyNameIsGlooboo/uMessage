package com.glooboo.umessage.ui.scheduled

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.helpers.DateStyle
import com.glooboo.umessage.helpers.w3cPrefs
import com.glooboo.umessage.messaging.RepeatRule
import com.glooboo.umessage.ui.components.ChoiceDialog
import com.glooboo.umessage.ui.components.ConfirmDialog
import com.glooboo.umessage.ui.components.EmptyState
import com.glooboo.umessage.ui.components.LoadingState
import com.glooboo.umessage.ui.components.UMessageScaffold
import com.glooboo.umessage.ui.components.UMessageTopBar
import com.glooboo.umessage.ui.icons.Symbols
import com.glooboo.umessage.ui.thread.fullDateLabel

/** What the user asked of a row and still has to confirm. */
private sealed interface Pending {
    val row: ScheduledRow

    data class Send(override val row: ScheduledRow) : Pending
    data class Delete(override val row: ScheduledRow) : Pending
}

/** The string for a repeat rule under a scheduled message; a held message says its repeat is paused. */
internal fun repeatNoteRes(rule: RepeatRule, held: Boolean): Int? = when {
    rule == RepeatRule.NONE -> null
    held -> R.string.scheduled_repeats_paused
    rule == RepeatRule.DAILY -> R.string.scheduled_repeats_daily
    rule == RepeatRule.WEEKLY -> R.string.scheduled_repeats_weekly
    rule == RepeatRule.MONTHLY -> R.string.scheduled_repeats_monthly
    else -> R.string.scheduled_repeats_yearly
}

/**
 * Every scheduled message, a section for each day, those that could not be sent first. Tapping a row opens its
 * conversation; the row menu changes its time, sends it now or deletes it, each asking first where it cannot be undone.
 */
@Composable
fun ScheduledListScreen(
    state: ScheduledListState,
    onBack: () -> Unit,
    onOpen: (ScheduledRow) -> Unit,
    onEditTime: (ScheduledRow) -> Unit,
    onSendNow: (ScheduledRow) -> Unit,
    onDelete: (ScheduledRow, onlyThis: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pending by remember { mutableStateOf<Pending?>(null) }
    UMessageScaffold(
        modifier = modifier,
        topBar = { UMessageTopBar(title = stringResource(R.string.scheduled_list_title), onBack = onBack) },
    ) { padding ->
        when {
            state.loading -> LoadingState(Modifier.padding(padding))
            state.count == 0 -> EmptyState(
                icon = Symbols.ScheduleSend,
                title = stringResource(R.string.scheduled_list_empty_title),
                body = stringResource(R.string.scheduled_list_empty_body),
                modifier = Modifier.padding(padding),
            )
            else -> ScheduledRows(
                state = state,
                padding = padding,
                onOpen = onOpen,
                onEditTime = onEditTime,
                onAsk = { pending = it },
            )
        }
    }
    pending?.let { ask ->
        ScheduledConfirm(ask, onDismiss = { pending = null }, onSendNow = onSendNow, onDelete = onDelete)
    }
}

@Composable
private fun ScheduledRows(
    state: ScheduledListState,
    padding: PaddingValues,
    onOpen: (ScheduledRow) -> Unit,
    onEditTime: (ScheduledRow) -> Unit,
    onAsk: (Pending) -> Unit,
) {
    val style = LocalContext.current.w3cPrefs.dateStyle
    val locale = LocalConfiguration.current.locales[0]
    val actions = RowActions(
        onOpen = onOpen,
        onEditTime = onEditTime,
        onSendNow = { onAsk(Pending.Send(it)) },
        onDelete = { onAsk(Pending.Delete(it)) },
    )
    val contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding())
    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
        if (state.notSent.isNotEmpty()) {
            item(key = "not-sent") { SectionHeader(stringResource(R.string.scheduled_list_not_sent), error = true) }
            items(state.notSent, key = { it.id }) { ScheduledRowItem(it, actions) }
        }
        state.days.forEach { day ->
            item(key = "day-${day.date}") {
                val jalali = style != DateStyle.GREGORIAN
                SectionHeader(fullDateLabel(day.date, locale, jalali = jalali, both = style == DateStyle.BOTH))
            }
            items(day.rows, key = { it.id }) { ScheduledRowItem(it, actions) }
        }
    }
}

private class RowActions(
    val onOpen: (ScheduledRow) -> Unit,
    val onEditTime: (ScheduledRow) -> Unit,
    val onSendNow: (ScheduledRow) -> Unit,
    val onDelete: (ScheduledRow) -> Unit,
)

@Composable
private fun SectionHeader(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun ScheduledRowItem(row: ScheduledRow, actions: RowActions) {
    val context = LocalContext.current
    val time = DateUtils.formatDateTime(context, row.millis, DateUtils.FORMAT_SHOW_TIME)
    val note = repeatNoteRes(row.repeat, row.held)?.let { stringResource(it) }
    val approximate = stringResource(R.string.thread_scheduled_approximate).takeIf { row.approximate && !row.held }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clickable(role = Role.Button) { actions.onOpen(row) }
            .padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(row.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                row.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(time, approximate, note).joinToString(stringResource(R.string.ui_separator)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        RowMenu(row, actions)
    }
}

@Composable
private fun RowMenu(row: ScheduledRow, actions: RowActions) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Symbols.MoreVert, contentDescription = stringResource(R.string.scheduled_list_row_actions, row.title))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.scheduled_list_edit_time)) },
                leadingIcon = { Icon(Symbols.Schedule, contentDescription = null) },
                onClick = {
                    open = false
                    actions.onEditTime(row)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.scheduled_list_send_now)) },
                leadingIcon = { Icon(Symbols.Send, contentDescription = null) },
                onClick = {
                    open = false
                    actions.onSendNow(row)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.scheduled_list_delete)) },
                leadingIcon = { Icon(Symbols.Delete, contentDescription = null) },
                onClick = {
                    open = false
                    actions.onDelete(row)
                },
            )
        }
    }
}

/** Send now names the person; delete asks, and a repeating message asks whether only this one goes. */
@Composable
private fun ScheduledConfirm(
    ask: Pending,
    onDismiss: () -> Unit,
    onSendNow: (ScheduledRow) -> Unit,
    onDelete: (ScheduledRow, onlyThis: Boolean) -> Unit,
) {
    val row = ask.row
    when {
        ask is Pending.Send -> ConfirmDialog(
            message = stringResource(R.string.scheduled_list_send_confirm, row.title),
            confirmLabel = stringResource(R.string.scheduled_list_send_now),
            onConfirm = {
                onDismiss()
                onSendNow(row)
            },
            onDismiss = onDismiss,
        )
        row.repeat != RepeatRule.NONE && !row.held -> ChoiceDialog(
            options = listOf(
                stringResource(R.string.scheduled_delete_this_one),
                stringResource(R.string.scheduled_delete_all_future),
            ),
            selectedIndex = -1,
            onSelect = {
                onDismiss()
                onDelete(row, it == 0)
            },
            onDismiss = onDismiss,
            title = stringResource(R.string.scheduled_delete_repeating),
        )
        else -> ConfirmDialog(
            message = stringResource(R.string.scheduled_list_delete_confirm, row.title),
            confirmLabel = stringResource(R.string.scheduled_list_delete),
            destructive = true,
            onConfirm = {
                onDismiss()
                onDelete(row, false)
            },
            onDismiss = onDismiss,
        )
    }
}

package com.glooboo.umessage.ui.spam

import android.content.res.Resources
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.glooboo.umessage.R
import com.glooboo.umessage.ui.conversations.ConversationFolder
import com.glooboo.umessage.ui.conversations.ConversationFolderScreen
import com.glooboo.umessage.ui.conversations.ConversationRow
import com.glooboo.umessage.ui.conversations.FolderMenuEntry
import com.glooboo.umessage.ui.conversations.SpamFolderActions
import com.glooboo.umessage.ui.conversations.SwipeAction
import com.glooboo.umessage.ui.icons.Symbols
import com.glooboo.umessage.spam.engine.SpamCause
import kotlinx.coroutines.launch

/**
 * Provider truth, the Spam tab (4.2): the chats in Spam on the shared folder screen, with "Not spam", "Unblock", Block,
 * Delete and Mark as read; the strip while the filter is off; and, while the message migration waits, the entry to the
 * older spam kept only in uMessage (U20). [reloadKey] changes whenever the Messages list was published, so a new spam
 * text shows here too.
 */
@Composable
fun SpamChatsRoute(
    onOpen: (ConversationRow) -> Unit,
    onOpenRules: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    reloadKey: Any? = null,
    viewModel: SpamChatsViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    var olderOpen by rememberSaveable { mutableStateOf(false) }
    LifecycleResumeEffect(viewModel) {
        viewModel.load()
        onPauseOrDispose { }
    }
    LaunchedEffect(reloadKey) { viewModel.load() }
    LaunchedEffect(viewModel) {
        // Each event in its own job: a new snackbar dismisses the one before, which then commits its action.
        viewModel.events.collect { event -> launch { showSpamEvent(event, snackbar, viewModel, resources) } }
    }
    state.nameConfirm?.let { ask ->
        NameAllowDialog(
            ask.names,
            onAllow = { viewModel.answerNames(true) },
            onDismiss = { viewModel.answerNames(false) },
        )
    }
    val pendingSwitch by viewModel.switches.pending.collectAsStateWithLifecycle()
    pendingSwitch?.let { SwitchConfirmDialog(it, viewModel.switches::confirm, viewModel.switches::dismiss) }

    if (olderOpen && state.olderSpam > 0) {
        BackHandler { olderOpen = false }
        OlderSpamRoute(onBack = { olderOpen = false }, modifier = modifier)
        return
    }
    val notSpamLabel = stringResource(R.string.ux8c_not_spam)
    val unblockLabel = stringResource(R.string.blocknum_unblock)
    val spam = remember(viewModel, notSpamLabel, unblockLabel) {
        val notSpam = SwipeAction(Symbols.Inbox, notSpamLabel) { viewModel.notSpam(setOf(it)) }
        val unblock = SwipeAction(Symbols.Shield, unblockLabel) { viewModel.unblock(setOf(it)) }
        SpamFolderActions(
            selection = { ids -> viewModel.state.value.selection(ids) },
            onNotSpam = viewModel::notSpam,
            onUnblock = viewModel::unblock,
            onBlock = viewModel::block,
            onMarkRead = viewModel::markRead,
            mayDelete = {
                viewModel.canDelete().also { if (!it) viewModel.refuseDelete() }
            },
            swipeFor = { row ->
                val cause = viewModel.state.value.causes[row.threadId] ?: SpamCause.UNKNOWN
                if (SpamChatsPolicy.swipeUnblocks(cause)) unblock else notSpam
            },
        )
    }
    val menu = remember(onOpenRules, onOpenSettings) {
        listOf(
            FolderMenuEntry(R.string.spam_blocked_open_settings, Symbols.Settings, onOpenSettings),
            FolderMenuEntry(R.string.settings_spam_open_rules, Symbols.Rule, onOpenRules),
        )
    }
    val showStrip = !state.filterOn || state.olderSpam > 0
    ConversationFolderScreen(
        folder = ConversationFolder.Spam,
        rows = state.rows,
        loading = state.loading,
        onBack = null,
        onOpen = onOpen,
        onRestore = {},
        onDelete = viewModel::delete,
        onEmpty = {},
        modifier = modifier,
        snackbarHostState = snackbar,
        onMarkAllRead = viewModel::markAllRead,
        intro = if (showStrip) {
            {
                SpamStrips(
                    filterOn = state.filterOn,
                    olderSpam = state.olderSpam,
                    onTurnOn = { viewModel.switches.request(SortChange.Filter(on = true)) },
                    onOpenOlder = { olderOpen = true },
                )
            }
        } else {
            null
        },
        spam = spam,
        menu = menu,
    )
}

/** The strips above the Spam list: the filter is off (with "Turn on"), and the older spam entry (U20). */
@Composable
internal fun SpamStrips(
    filterOn: Boolean,
    olderSpam: Int,
    onTurnOn: () -> Unit,
    onOpenOlder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        if (!filterOn) {
            SpamStrip(
                title = stringResource(R.string.pt_spam_filter_off_strip),
                body = stringResource(R.string.pt_spam_filter_off_body),
                action = stringResource(R.string.cleanup_bin_confirm),
                onAction = onTurnOn,
            )
        }
        if (olderSpam > 0) {
            val count = LocalResources.current.getQuantityString(R.plurals.spam_message_count, olderSpam, olderSpam)
            SpamStrip(
                title = stringResource(R.string.pt_older_spam_title),
                body = count,
                action = null,
                onAction = onOpenOlder,
            )
        }
    }
}

@Composable
private fun SpamStrip(title: String, body: String, action: String?, onAction: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onAction,
        shape = RoundedCornerShape(12.dp),
        color = colors.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(
            Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
                Text(body, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            if (action != null) {
                TextButton(onClick = onAction) { Text(action, fontWeight = FontWeight.SemiBold) }
            } else {
                Icon(Symbols.ChevronRight, contentDescription = null, tint = colors.onSurfaceVariant)
            }
        }
    }
}

/** The snackbar of one Spam event; its Undo (or "Not spam") answer goes back to [viewModel]. */
private suspend fun showSpamEvent(
    event: SpamChatsEvent,
    snackbar: SnackbarHostState,
    viewModel: SpamChatsViewModel,
    resources: Resources,
) {
    val offer = SnackbarOffer(snackbar, resources.getString(R.string.spam_undo))
    when (event) {
        is SpamChatsEvent.MovedToMessages -> {
            val text = if (event.name != null) {
                resources.getString(R.string.nsf_snack_moved_allowed, event.name)
            } else {
                resources.getQuantityString(R.plurals.nsf_snack_moved_allowed_many, event.count, event.count)
            }
            if (offer(text)) viewModel.undoNotSpam(event.token) else viewModel.commitNotSpam(event.token)
        }
        is SpamChatsEvent.Unblocked, is SpamChatsEvent.StillUnknown, is SpamChatsEvent.StillOnSystemList ->
            showUnblockEvent(event, offer, viewModel, resources)
        is SpamChatsEvent.Blocked -> {
            if (offer(resources.getString(R.string.e_note_blocked_by_you))) {
                viewModel.undoBlock(event.token)
            } else {
                viewModel.commitBlock(event.token)
            }
        }
        is SpamChatsEvent.Deleted -> {
            val text = resources.getQuantityString(R.plurals.convlist_deleted, event.count, event.count)
            if (offer(text, long = true)) viewModel.undoDelete(event.token) else viewModel.commitDelete(event.token)
        }
        is SpamChatsEvent.MarkedAllRead -> snackbar.showSnackbar(
            resources.getQuantityString(R.plurals.decm_marked_all_read, event.count, event.count),
        )
        is SpamChatsEvent.Message -> snackbar.showSnackbar(resources.getString(event.res))
        SpamChatsEvent.Failed ->
            snackbar.showSnackbar(resources.getString(org.fossify.commons.R.string.unknown_error_occurred))
    }
}

/** A snackbar with one action (Undo by default) that replaces the one shown; true when the action was tapped. */
private class SnackbarOffer(private val snackbar: SnackbarHostState, private val undo: String) {
    suspend operator fun invoke(message: String, action: String = undo, long: Boolean = false): Boolean {
        snackbar.currentSnackbarData?.dismiss()
        val duration = if (long) SnackbarDuration.Long else SnackbarDuration.Short
        val result = snackbar.showSnackbar(message, actionLabel = action, duration = duration)
        return result == SnackbarResult.ActionPerformed
    }
}

/** "Unblock" (4.2): back in Messages with Undo; still unknown with "Not spam"; still on Android's own block list. */
private suspend fun showUnblockEvent(
    event: SpamChatsEvent,
    offer: SnackbarOffer,
    viewModel: SpamChatsViewModel,
    resources: Resources,
) {
    when (event) {
        is SpamChatsEvent.Unblocked -> {
            val text = resources.getQuantityString(R.plurals.pt_snack_unblocked, event.count, event.count)
            if (offer(text)) viewModel.undoUnblock(event.token) else viewModel.commitUnblock(event.token)
        }
        is SpamChatsEvent.StillUnknown -> {
            // Unblocked, but still unknown: the one action is "Not spam".
            viewModel.commitUnblock(event.token)
            val text = resources.getString(R.string.thread_unblock_done_filtered)
            if (offer(text, resources.getString(R.string.ux8c_not_spam), long = true)) viewModel.notSpam(event.ids)
        }
        is SpamChatsEvent.StillOnSystemList -> {
            val text = resources.getString(R.string.thread_unblock_done_system)
            if (offer(text, long = true)) viewModel.undoUnblock(event.token) else viewModel.commitUnblock(event.token)
        }
        else -> Unit
    }
}

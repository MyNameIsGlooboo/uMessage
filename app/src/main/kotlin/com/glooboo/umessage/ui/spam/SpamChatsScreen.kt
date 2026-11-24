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
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.glooboo.umessage.R
import com.glooboo.umessage.spam.UnknownSenderMode
import com.glooboo.umessage.spam.engine.SpamCause
import com.glooboo.umessage.ui.components.rememberReduceMotion
import com.glooboo.umessage.ui.conversations.ConversationFolder
import com.glooboo.umessage.ui.conversations.ConversationFolderScreen
import com.glooboo.umessage.ui.conversations.ConversationRow
import com.glooboo.umessage.ui.conversations.FolderMenuEntry
import com.glooboo.umessage.ui.conversations.SpamFolderActions
import com.glooboo.umessage.ui.conversations.SwipeAction
import com.glooboo.umessage.ui.conversations.commitIfCancelled
import com.glooboo.umessage.ui.icons.Symbols
import com.glooboo.umessage.ui.onboarding.SortingCard
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Whether the empty Spam tab's sorting card has played since the app was opened (it plays once per open). */
private var spamCardPlayed = false

/**
 * Provider truth, the Spam tab (4.2): the chats in Spam on the shared folder screen, with "Not spam", "Unblock", Block,
 * Delete and Mark as read; the first-time tip; the strip while the filter is off; the sorting card while it is empty;
 * and, while the message migration waits, the entry to the older spam kept only in uMessage (U20). [reloadKey]
 * changes whenever the Messages list was published, so a new spam text shows here too. [onViewRule] is the "View rule"
 * of a refused Unblock: the rules screen on its block list (D-6).
 */
@Composable
fun SpamChatsRoute(
    onOpen: (ConversationRow) -> Unit,
    onOpenRules: () -> Unit,
    onViewRule: () -> Unit,
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
    val viewRule by rememberUpdatedState(onViewRule)
    LaunchedEffect(viewModel) {
        // No snackbar of an earlier screen is on show: its open windows close (C-1, C-2).
        viewModel.onRouteStarted()
        // Each event in its own job: a new snackbar dismisses the one before, which then commits its action.
        viewModel.events.collect { event ->
            launch { showSpamEvent(event, snackbar, viewModel, resources, onViewRule = { viewRule() }) }
        }
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
    val spam = rememberSpamActions(viewModel, state.deleteOff)
    val menu = remember(onOpenRules, onOpenSettings) {
        listOf(
            FolderMenuEntry(R.string.spam_blocked_open_settings, Symbols.Settings, onOpenSettings),
            FolderMenuEntry(R.string.settings_spam_open_rules, Symbols.Rule, onOpenRules),
        )
    }
    val empty = state.rows.isEmpty() && !state.loading
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
        intro = {
            SpamStrips(
                filterOn = state.filterOn,
                olderSpam = state.olderSpam,
                onTurnOn = { viewModel.switches.request(SortChange.Filter(on = true)) },
                onOpenOlder = { olderOpen = true },
                empty = empty,
            )
        },
        spam = spam,
        menu = menu,
    )
}

@Composable
private fun rememberSpamActions(viewModel: SpamChatsViewModel, deleteOff: Int?): SpamFolderActions {
    val notSpamLabel = stringResource(R.string.ux8c_not_spam)
    val unblockLabel = stringResource(R.string.blocknum_unblock)
    return remember(viewModel, notSpamLabel, unblockLabel, deleteOff) {
        val notSpam = SwipeAction(Symbols.Inbox, notSpamLabel) { viewModel.notSpam(setOf(it)) }
        val unblock = SwipeAction(Symbols.Shield, unblockLabel) { viewModel.unblock(setOf(it)) }
        SpamFolderActions(
            selection = { ids -> viewModel.state.value.selection(ids) },
            onNotSpam = { viewModel.notSpam(it) },
            onUnblock = viewModel::unblock,
            onBlock = viewModel::block,
            onMarkRead = viewModel::markRead,
            deleteOff = deleteOff,
            swipeFor = { row ->
                val cause = viewModel.state.value.causes[row.threadId] ?: SpamCause.UNKNOWN
                if (SpamChatsPolicy.swipeUnblocks(cause)) unblock else notSpam
            },
        )
    }
}

/** Test tag of the sorting card in the empty Spam tab. */
internal const val SPAM_EMPTY_CARD_TAG = "spam_empty_card"

/**
 * Above the Spam list: the first-time tip (with rows), the filter-off strip (with "Turn on"), the older spam entry
 * (U20) and, while the tab is [empty], the sorting card that shows what uMessage is doing (owner decision of 18 Nov).
 */
@Composable
internal fun SpamStrips(
    filterOn: Boolean,
    olderSpam: Int,
    onTurnOn: () -> Unit,
    onOpenOlder: () -> Unit,
    modifier: Modifier = Modifier,
    empty: Boolean = false,
) {
    Column(modifier) {
        if (!empty) BlockedTip(Modifier.padding(vertical = 4.dp))
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
        if (empty) SpamEmptyCard(filterOn)
    }
}

/** The sorting card of the empty Spam tab: plays once each time the app is opened, then shows finished. */
@Composable
private fun SpamEmptyCard(filterOn: Boolean) {
    val mode = rememberSortingCardMode(if (filterOn) UnknownSenderMode.SMART else UnknownSenderMode.OFF) ?: return
    val autoPlay = remember { !spamCardPlayed }
    LaunchedEffect(Unit) { spamCardPlayed = true }
    SortingCard(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag(SPAM_EMPTY_CARD_TAG),
        mode = mode,
        autoPlay = autoPlay,
        reduceMotion = rememberReduceMotion(),
    )
}

/** One strip; C-18: one click target per action, a button role when the whole strip is the action. */
@Composable
private fun SpamStrip(title: String, body: String, action: String?, onAction: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    val outer = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
    val content: @Composable () -> Unit = {
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
    if (action != null) {
        Surface(shape = shape, color = colors.surfaceContainerHigh, modifier = outer, content = content)
    } else {
        Surface(
            onClick = onAction,
            shape = shape,
            color = colors.surfaceContainerHigh,
            modifier = outer.semantics { role = Role.Button },
            content = content,
        )
    }
}

/** The snackbar of one Spam event; its Undo (or "Not spam") answer goes back to [viewModel]. */
internal suspend fun showSpamEvent(
    event: SpamChatsEvent,
    snackbar: SnackbarHostState,
    viewModel: SpamChatsViewModel,
    resources: Resources,
    onViewRule: () -> Unit,
) {
    val offer = SnackbarOffer(snackbar, resources.getString(R.string.spam_undo))
    when (event) {
        // C-1, C-2: a window whose snackbar goes away unanswered (tab left, rotation) is closed, never left open; a
        // delete keeps its window instead and is offered again when the route starts (D-1).
        is SpamChatsEvent.Windowed ->
            commitIfCancelled({ viewModel.commit(event) }) { showWindowed(event, offer, viewModel, resources) }
        is SpamChatsEvent.MarkedAllRead -> snackbar.showSnackbar(
            resources.getQuantityString(R.plurals.decm_marked_all_read, event.count, event.count),
        )
        is SpamChatsEvent.Message -> showMessage(event, snackbar, resources, onViewRule)
        is SpamChatsEvent.KeptNewText -> snackbar.showSnackbar(
            resources.getQuantityString(R.plurals.spam_delete_kept_new_text, event.count, event.count),
        )
        is SpamChatsEvent.KeptOther -> snackbar.showSnackbar(
            resources.getQuantityString(R.plurals.spam_delete_kept_other, event.count, event.count),
        )
        SpamChatsEvent.Failed ->
            snackbar.showSnackbar(resources.getString(org.fossify.commons.R.string.unknown_error_occurred))
    }
}

/** A plain message; with [SpamChatsEvent.Message.viewRule] its "View rule" action calls [onViewRule] (D-6). */
private suspend fun showMessage(
    event: SpamChatsEvent.Message,
    snackbar: SnackbarHostState,
    resources: Resources,
    onViewRule: () -> Unit,
) {
    val text = if (event.arg != null) resources.getString(event.res, event.arg) else resources.getString(event.res)
    if (!event.viewRule) {
        snackbar.showSnackbar(text)
        return
    }
    // L-7: the reason shows at once, not after an Undo window that is still open.
    snackbar.currentSnackbarData?.dismiss()
    val result = snackbar.showSnackbar(
        text,
        actionLabel = resources.getString(R.string.e_view_rule),
        duration = SnackbarDuration.Long,
    )
    if (result == SnackbarResult.ActionPerformed) onViewRule()
}

/**
 * J24: the visuals of a snackbar made here. [SnackbarHostState.currentSnackbarData] carries them back, so a caller can
 * tell its own snackbar from another one before it dismisses (see [dismissIfShown]).
 */
internal class OwnedSnackbar(
    override val message: String,
    override val actionLabel: String?,
    override val duration: SnackbarDuration,
    override val withDismissAction: Boolean = false,
) : SnackbarVisuals

/** Dismisses the snackbar of [visuals] when it is the one on screen; any other snackbar stays. */
internal fun SnackbarHostState.dismissIfShown(visuals: SnackbarVisuals) {
    currentSnackbarData?.takeIf { it.visuals === visuals }?.dismiss()
}

/** A snackbar with one action (Undo by default) that replaces the one shown; true when the action was tapped. */
private class SnackbarOffer(private val snackbar: SnackbarHostState, private val undo: String) {
    /** The snackbar this offer showed last: [dismiss] never dismisses another one. */
    private var shown: SnackbarVisuals? = null

    fun dismiss() {
        shown?.let { snackbar.dismissIfShown(it) }
    }

    suspend operator fun invoke(message: String, action: String = undo, long: Boolean = false): Boolean {
        snackbar.currentSnackbarData?.dismiss()
        val duration = if (long) SnackbarDuration.Long else SnackbarDuration.Short
        val visuals = OwnedSnackbar(message, action, duration)
        shown = visuals
        return snackbar.showSnackbar(visuals) == SnackbarResult.ActionPerformed
    }
}

private suspend fun showWindowed(
    event: SpamChatsEvent.Windowed,
    offer: SnackbarOffer,
    viewModel: SpamChatsViewModel,
    resources: Resources,
) {
    when (event) {
        is SpamChatsEvent.MovedToMessages -> {
            val text = if (event.archived) {
                resources.getString(R.string.spam_moved_to_archived)
            } else if (event.name != null) {
                resources.getString(R.string.nsf_snack_moved_allowed, event.name)
            } else {
                resources.getQuantityString(R.plurals.nsf_snack_moved_allowed_many, event.count, event.count)
            }
            if (offer(text)) viewModel.undoNotSpam(event.token) else viewModel.commitNotSpam(event.token)
        }
        is SpamChatsEvent.Blocked -> {
            if (offer(resources.getString(R.string.e_note_blocked_by_you))) {
                viewModel.undoBlock(event.token)
            } else {
                viewModel.commitBlock(event.token)
            }
        }
        is SpamChatsEvent.Deleted -> {
            val text = resources.getQuantityString(R.plurals.convlist_deleted, event.count, event.count)
            // L3-2: when the delete runs before the snackbar ends (the app left, a new delete), its Undo goes away.
            val undone = coroutineScope {
                val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                    viewModel.deleteClosed.first { it == event.token }
                    offer.dismiss()
                }
                try {
                    viewModel.isDeletePending(event.token) && offer(text, long = true)
                } finally {
                    watcher.cancel()
                }
            }
            if (undone) viewModel.undoDelete(event.token) else viewModel.commitDelete(event.token)
        }
        else -> showUnblockEvent(event, offer, viewModel, resources)
    }
}

/** "Unblock" (4.2, C-4): back in Messages, still unknown (with "Not spam"), on Android's list, or not sure where. */
private suspend fun showUnblockEvent(
    event: SpamChatsEvent.Windowed,
    offer: SnackbarOffer,
    viewModel: SpamChatsViewModel,
    resources: Resources,
) {
    when (event) {
        is SpamChatsEvent.Unblocked -> {
            val text = if (event.archived) {
                resources.getString(R.string.spam_moved_to_archived)
            } else {
                resources.getQuantityString(R.plurals.pt_snack_unblocked, event.count, event.count)
            }
            if (offer(text)) viewModel.undoUnblock(event.token) else viewModel.commitUnblock(event.token)
        }
        is SpamChatsEvent.UnblockedUnsure -> {
            val text = resources.getQuantityString(R.plurals.blocknum_unblocked, event.count, event.count)
            if (offer(text)) viewModel.undoUnblock(event.token) else viewModel.commitUnblock(event.token)
        }
        is SpamChatsEvent.StillUnknown -> {
            // Unblocked, but still unknown: the one action is "Not spam" (C-15: the saved cause may still be BLOCKED).
            viewModel.commitUnblock(event.token)
            val text = resources.getString(R.string.thread_unblock_done_filtered)
            if (offer(text, resources.getString(R.string.ux8c_not_spam), long = true)) {
                viewModel.notSpam(event.ids, anyCause = true)
            }
        }
        is SpamChatsEvent.StillOnSystemList -> {
            val text = resources.getString(R.string.thread_unblock_done_system)
            if (offer(text, long = true)) viewModel.undoUnblock(event.token) else viewModel.commitUnblock(event.token)
        }
        else -> Unit
    }
}

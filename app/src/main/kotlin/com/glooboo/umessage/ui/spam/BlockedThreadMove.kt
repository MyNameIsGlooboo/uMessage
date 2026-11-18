package com.glooboo.umessage.ui.spam

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.ui.components.EmptyState
import kotlinx.coroutines.delay

private const val CLOSE_GRACE_MS = 250L

/** The thread's rows while a move runs: dimmed, so they read as "on their way". */
private const val MOVING_ALPHA = 0.5f

/**
 * The messages the sender page draws: the ones still held plus the ones being moved (they stay on the page, dimmed,
 * until the move is over), newest first.
 */
internal fun threadMessages(live: SenderGroup?, moving: SenderGroup?): List<BlockedMessage> =
    (moving?.messages.orEmpty() + live?.messages.orEmpty()).distinctBy { it.id }.sortedWith(NEWEST_FIRST)

/** Newest first; messages of the same time keep one order (by id), so bubbles do not swap when the list changes. */
internal val NEWEST_FIRST = compareByDescending<BlockedMessage> { it.receivedAt }.thenByDescending { it.id }

/** The sender thread when there is nothing to list: loading, or the sender is gone. */
@Composable
internal fun ThreadPlaceholder(loading: Boolean, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        if (loading) {
            val label = stringResource(R.string.spam_loading)
            CircularProgressIndicator(Modifier.semantics { contentDescription = label })
        } else {
            EmptyState(
                iconRes = R.drawable.ic_spam_shield_check,
                title = stringResource(R.string.r3b_thread_empty_title),
                body = stringResource(R.string.r3b_thread_empty_body),
            )
        }
    }
}

/**
 * The sender page's reactions to the shared move state. [idle]: the model has loaded and no undo window is open, so a
 * [moving] left over from before process death is stale and [onResetMoving] clears it. A page that was open while
 * somebody else's move ran (and did not start one itself) calls [onOthersMoveEnd] when that move is over.
 */
@Composable
internal fun MoveWatch(
    movingHere: Boolean,
    moving: Boolean,
    idle: Boolean,
    onResetMoving: () -> Unit,
    onOthersMoveEnd: () -> Unit,
) {
    var sawMove by rememberSaveable { mutableStateOf(false) }
    val reset by rememberUpdatedState(onResetMoving)
    val ended by rememberUpdatedState(onOthersMoveEnd)
    LaunchedEffect(idle, movingHere) { if (idle && !movingHere) reset() }
    // Only a move this page did not start counts: its own "Not spam" ends by opening the chat, which closes the page.
    LaunchedEffect(movingHere) { if (movingHere && !moving) sawMove = true }
    LaunchedEffect(sawMove, movingHere, moving) { if (sawMove && !movingHere && !moving) ended() }
}

/** Closes the page once it has [settled] and either the user chose to leave it empty or it never held anything. */
@Composable
internal fun CloseWhenSettled(settled: Boolean, closeWhenEmpty: Boolean, seen: Boolean, onClose: () -> Unit) {
    val close by rememberUpdatedState(onClose)
    LaunchedEffect(settled, closeWhenEmpty, seen) {
        if (settled && (closeWhenEmpty || !seen)) {
            // spamui-22: a short grace, so a snackbar queued at this very moment (it flips [settled] back and
            // restarts this effect) is shown before the screen goes.
            delay(CLOSE_GRACE_MS)
            close()
        }
    }
}

/** The held messages as a bottom-up list; with [enabled] false (a move runs) it is dimmed and bubbles do nothing. */
@Composable
internal fun ThreadMessageList(
    messages: List<BlockedMessage>,
    sims: List<SimInfo>,
    actions: BlockedThreadActions,
    selection: Set<Long>,
    enabled: Boolean,
    onWhy: (Long) -> Unit,
    onToggle: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows = remember(messages) { blockedThreadItems(messages) }
    val heldLines = remember(messages) { heldLineShownFor(messages) }
    LazyColumn(
        modifier = if (enabled) modifier else modifier.alpha(MOVING_ALPHA),
        reverseLayout = true,
        contentPadding = PaddingValues(top = 8.dp, bottom = 12.dp),
    ) {
        items(rows, key = { it.key }) { item ->
            when (item) {
                is BlockedThreadItem.Day -> ThreadDayHeader(item.day)
                is BlockedThreadItem.Bubble -> HeldBubble(
                    message = item.message,
                    showHeldLine = item.message.id in heldLines,
                    simLabel = simLabel(sims, item.message.subscriptionId),
                    actions = actions,
                    onWhy = { onWhy(item.message.id) },
                    selecting = selection.isNotEmpty(),
                    selected = item.message.id in selection,
                    onToggle = { onToggle(item.message.id) },
                    enabled = enabled,
                )
            }
        }
    }
}

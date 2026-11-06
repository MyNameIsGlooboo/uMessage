package com.glooboo.umessage.ui.conversations

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.runtime.withFrameNanos
import com.glooboo.umessage.ui.icons.Symbols
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.glooboo.umessage.R
import com.glooboo.umessage.ui.components.EmptyState
import com.glooboo.umessage.ui.components.fabListBottomPadding
import com.glooboo.umessage.ui.components.readableOn
import com.glooboo.umessage.ui.main.InboxFilter
import com.glooboo.umessage.ui.main.InboxFilterPolicy
import com.glooboo.umessage.ui.main.filterTile
import com.glooboo.umessage.ui.components.LoadingState
import com.glooboo.umessage.ui.components.rememberReduceMotion
import kotlin.math.roundToInt

/** Connects the ViewModel to the stateless [ConversationsScreen] and turns its events into snackbars. */
@Composable
fun ConversationsRoute(
    viewModel: ConversationsViewModel,
    callbacks: ConversationsCallbacks,
    modifier: Modifier = Modifier,
    /** CL-13, N11: show only the conversations that belong to this filter. */
    filter: InboxFilter = InboxFilter.All,
    /** I28: the conversations the Unknown, Starred, Images and Links filters keep. */
    filterThreads: Set<Long> = emptySet(),
    /** B10: cards and filter chips shown above the rows; they scroll away with the list. */
    header: (@Composable () -> Unit)? = null,
    /** B06: the "Hidden chats" line under the last row. */
    footer: (@Composable () -> Unit)? = null,
) {
    val fullState by viewModel.state.collectAsStateWithLifecycle()
    val state = remember(fullState, filter, filterThreads) {
        if (filter == InboxFilter.All) {
            fullState
        } else {
            fullState.copy(rows = InboxFilterPolicy.filterRows(fullState.rows, filter, filterThreads))
        }
    }
    val snackbar = remember { SnackbarHostState() }

    val undoLabel = stringResource(R.string.convlist_undo)
    val failedText = stringResource(R.string.convlist_action_failed)
    val resources = LocalContext.current.resources

    LaunchedEffect(viewModel) {
        // After a rotation the previous snackbar was cancelled unanswered; offer its Undo again.
        viewModel.replayPendingUndo()
        // One coroutine per event so a new snackbar can replace the one still showing at once.
        coroutineScope {
            viewModel.events.collect { event ->
                snackbar.currentSnackbarData?.dismiss()
                launch { showEvent(event, snackbar, callbacks, resources, undoLabel, failedText) }
            }
        }
    }

    ConversationsScreen(
        state = state,
        snackbarHostState = snackbar,
        callbacks = callbacks,
        modifier = modifier,
        filter = filter,
        header = header,
        footer = footer,
    )
}

private suspend fun showEvent(
    event: ConversationsEvent,
    snackbar: SnackbarHostState,
    callbacks: ConversationsCallbacks,
    resources: android.content.res.Resources,
    undoLabel: String,
    failedText: String,
) {
    when (event) {
        is ConversationsEvent.Deleted -> {
            val result = snackbar.showSnackbar(
                message = resources.getQuantityString(R.plurals.convlist_deleted, event.count, event.count),
                actionLabel = undoLabel,
                withDismissAction = false,
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) {
                callbacks.onUndoDelete(event.token)
            } else {
                callbacks.onCommitDelete(event.token)
            }
        }
        is ConversationsEvent.Archived -> {
            val result = snackbar.showSnackbar(
                // P3-02: says where the chat went; Undo stays the only action.
                message = resources.getQuantityString(R.plurals.w2b1_archived_find, event.count, event.count),
                actionLabel = undoLabel,
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) callbacks.onUndoArchive(event.token)
        }
        is ConversationsEvent.Blocked -> {
            val result = snackbar.showSnackbar(
                message = resources.getQuantityString(R.plurals.convlist_blocked_done, event.count, event.count),
                actionLabel = undoLabel,
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) {
                callbacks.onUndoReportSpam(event.token)
            } else {
                callbacks.onCommitReportSpam(event.token)
            }
        }
        is ConversationsEvent.ReportedSpam -> {
            val text = if (event.archived) {
                R.plurals.convlist_reported_spam_archived
            } else {
                R.plurals.convlist_reported_spam
            }
            val result = snackbar.showSnackbar(
                message = resources.getQuantityString(text, event.count, event.count),
                actionLabel = undoLabel,
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) {
                callbacks.onUndoReportSpam(event.token)
            } else {
                callbacks.onCommitReportSpam(event.token)
            }
        }
        ConversationsEvent.Failed -> snackbar.showSnackbar(failedText)
    }
}

@Composable
fun ConversationsScreen(
    state: ConversationsUiState,
    snackbarHostState: SnackbarHostState,
    callbacks: ConversationsCallbacks,
    modifier: Modifier = Modifier,
    /** The rows are filtered: an empty list then says so instead of offering a first chat. */
    filter: InboxFilter = InboxFilter.All,
    /** Shown above the rows and scrolled with them; above the empty or loading state when there are no rows. */
    header: (@Composable () -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
) {
    val listState = rememberLazyListState()
    val reduceMotion = rememberReduceMotion()

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState, Modifier.navigationBarsPadding()) },
        floatingActionButton = {
            // Google Messages hides the FAB while conversations are selected.
            NewConversationFab(
                visible = !state.selecting,
                listState = listState,
                reduceMotion = reduceMotion,
                onNewConversation = callbacks.onNewConversation,
            )
        },
    ) { padding ->
        val showList = !(state.loading || state.importing) && state.rows.isNotEmpty()
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (!showList) header?.invoke()
            // With no rows to scroll, the line sits under the header, so hidden chats stay reachable.
            if (!showList) footer?.invoke()
            Box(Modifier.weight(1f).fillMaxWidth()) {
                ConversationsContent(state, filter, listState, callbacks, reduceMotion, header, footer)
            }
        }
    }
}

/** What the list area shows: the loading state, an empty state for the filter, or the rows. */
@Composable
private fun BoxScope.ConversationsContent(
    state: ConversationsUiState,
    filter: InboxFilter,
    listState: LazyListState,
    callbacks: ConversationsCallbacks,
    reduceMotion: Boolean,
    header: (@Composable () -> Unit)?,
    footer: (@Composable () -> Unit)?,
) {
    val tile = filterTile(filter)
    when {
        state.loading || state.importing -> LoadingState(label = stringResource(R.string.convlist_loading))
        state.rows.isEmpty() && tile != null -> EmptyState(
            icon = tile.icon,
            title = stringResource(tile.emptyTitleRes),
            body = stringResource(tile.emptyBodyRes),
            modifier = Modifier.align(Alignment.Center),
        )
        state.rows.isEmpty() && filter == InboxFilter.Label -> EmptyState(
            icon = Symbols.Tag,
            title = stringResource(R.string.i29_empty_title),
            body = stringResource(R.string.i29_empty_body),
            modifier = Modifier.align(Alignment.Center),
        )
        state.rows.isEmpty() && filter == InboxFilter.Sim -> EmptyState(
            icon = Symbols.SimCard,
            title = stringResource(R.string.search_page_sim_empty_title),
            body = stringResource(R.string.search_page_sim_empty_body),
            modifier = Modifier.align(Alignment.Center),
        )
        state.rows.isEmpty() -> EmptyConversations(callbacks)
        else -> ConversationList(state, listState, callbacks, reduceMotion, header, footer)
    }
}

/** Which way the list moved since the last position the FAB acted on. */
internal enum class FabScrollDirection { UP, DOWN, NONE }

/**
 * The direction from [fromIndex]/[fromOffset] to [toIndex]/[toOffset]. A change of first visible item decides it at
 * once; within one item, moves shorter than [jitterPx] are ignored so a resting finger or a fling's last pixel does not
 * flip the FAB back and forth.
 */
internal fun fabScrollDirection(
    fromIndex: Int,
    fromOffset: Int,
    toIndex: Int,
    toOffset: Int,
    jitterPx: Int,
): FabScrollDirection =
    when {
        toIndex > fromIndex -> FabScrollDirection.DOWN
        toIndex < fromIndex -> FabScrollDirection.UP
        toOffset - fromOffset > jitterPx -> FabScrollDirection.DOWN
        fromOffset - toOffset > jitterPx -> FabScrollDirection.UP
        else -> FabScrollDirection.NONE
    }

/** Shorter moves than this do not change the FAB (jitter). */
private val FabScrollJitter = 8.dp

/**
 * Owner note R3: the labelled "Start chat" FAB at the top of the list, as before, without the outline
 * UMessageFab draws on light pages. It shrinks to the icon while the list scrolls down (CL-01), so it does not cover
 * the dates and badges of the rows beneath it while reading, and grows back as soon as the list scrolls up.
 */
@Composable
private fun NewConversationFab(
    visible: Boolean,
    listState: LazyListState,
    reduceMotion: Boolean,
    onNewConversation: () -> Unit,
) {
    // At the very top it is always expanded. Derived from the list's position alone, so it is right again on the first
    // frame after a tab switch, a filter change or new rows.
    val atTop by remember(listState) {
        derivedStateOf {
            !listState.canScrollBackward ||
                (listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 24)
        }
    }
    // Elsewhere it follows the last scroll direction: expanded after scrolling up, folded after scrolling down.
    var scrolledUp by remember(listState) { mutableStateOf(false) }
    val jitterPx = with(LocalDensity.current) { FabScrollJitter.roundToPx() }
    LaunchedEffect(listState, jitterPx) {
        var anchorIndex = listState.firstVisibleItemIndex
        var anchorOffset = listState.firstVisibleItemScrollOffset
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                val direction = fabScrollDirection(anchorIndex, anchorOffset, index, offset, jitterPx)
                if (direction != FabScrollDirection.NONE) {
                    scrolledUp = direction == FabScrollDirection.UP
                    anchorIndex = index
                    anchorOffset = offset
                }
            }
    }
    val expanded = atTop || scrolledUp
    val fabLabel = stringResource(R.string.search_page_start_chat)
    AnimatedVisibility(
        visible = visible,
        enter = if (reduceMotion) EnterTransition.None else scaleIn() + fadeIn(),
        exit = if (reduceMotion) ExitTransition.None else scaleOut() + fadeOut(),
    ) {
        ExtendedFloatingActionButton(
            onClick = onNewConversation,
            expanded = expanded,
            icon = { Icon(Symbols.ChatBubble, contentDescription = null) },
            text = { Text(fabLabel) },
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            // U34: checked against the fill, so the label reads on any theme (a mid-blue fill took white at under
            // 4.5:1).
            contentColor = readableOn(
                MaterialTheme.colorScheme.primaryContainer,
                MaterialTheme.colorScheme.onPrimaryContainer,
            ),
            // The label leaves composition when the FAB collapses, so the accessible name is set here.
            modifier = Modifier
                .navigationBarsPadding()
                .heightIn(min = 56.dp)
                .clearAndSetSemantics {
                    contentDescription = fabLabel
                    role = Role.Button
                    onClick { onNewConversation(); true }
                },
        )
    }
}

@Composable
private fun EmptyConversations(callbacks: ConversationsCallbacks) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            EmptyState(
                icon = Symbols.ChatBubble,
                title = stringResource(R.string.convlist_empty_title),
                body = stringResource(R.string.convlist_empty_body),
                action = {
                    TextButton(onClick = callbacks.onNewConversation, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.convlist_start_conversation))
                    }
                },
            )
        }
    }
}

@Composable
private fun ConversationList(
    state: ConversationsUiState,
    listState: LazyListState,
    callbacks: ConversationsCallbacks,
    reduceMotion: Boolean,
    header: (@Composable () -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
) {
    // Keep the newest thread in view: when the user was at the top and a different thread becomes the first row
    // (a message was sent or received), LazyColumn would keep the old first row anchored and push the new one off
    // screen.
    // Both values are saveable: a rotation recreates the activity, and with plain `remember` the effect saw a "new"
    // top row and a reset "at top" flag on the first frame, so it threw the restored scroll position away.
    var userWasAtTop by rememberSaveable { mutableStateOf(true) }
    var lastTopThreadId by rememberSaveable { mutableStateOf<Long?>(null) }
    LaunchedEffect(listState) {
        snapshotFlow {
            Triple(
                listState.isScrollInProgress,
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset,
            )
        }.collect { (scrolling, index, offset) ->
            if (scrolling) userWasAtTop = index == 0 && offset < 24
        }
    }
    val topThreadId = state.rows.firstOrNull()?.threadId
    LaunchedEffect(topThreadId) {
        val previous = lastTopThreadId
        lastTopThreadId = topThreadId
        if (shouldReturnToTop(previous, topThreadId, userWasAtTop)) {
            // B15: let the list lay out the new rows first; it anchors on the old first row, then we move to the top.
            withFrameNanos { }
            listState.scrollToItem(0)
        }
    }
    // B15: coming back from a thread, the rows may have changed while the list was hidden; a user who was at the top
    // sees the top row again instead of the list anchored one row down.
    val scope = rememberCoroutineScope()
    LifecycleResumeEffect(listState) {
        if (userWasAtTop && listState.firstVisibleItemIndex != 0 && !listState.isScrollInProgress) {
            scope.launch { listState.scrollToItem(0) }
        }
        onPauseOrDispose { }
    }
    // CL-12: right archives, left blocks the sender (both undoable); a group chat has no single sender to block.
    val archiveLabel = stringResource(R.string.convlist_archive)
    val blockLabel = stringResource(R.string.w2b1_swipe_block)
    val archiveAction = remember(archiveLabel, callbacks) {
        SwipeAction(Symbols.Archive, archiveLabel) { callbacks.onArchiveOne(it) }
    }
    val blockAction = remember(blockLabel, callbacks) {
        SwipeAction(Symbols.Block, blockLabel, destructive = true) { callbacks.onBlockOne(it) }
    }
    val rowLabels = rememberRowLabels()
    // U55: only the newest fresh code gets its Copy chip, so the rows keep an even height.
    val codeChipThread = remember(state.rows) { newestCodeThread(state.rows) }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            // Room so the last row can scroll clear of the FAB. The tab bar below already covers the navigation bar.
            contentPadding = PaddingValues(bottom = fabListBottomPadding()),
        ) {
            // Never zero height: LazyColumn skips an empty first item, so at the top it reported the first conversation
            // as item 1 and anchored on it. A card appearing in the header then grew above the screen edge, and the
            // FAB, reading "not at item 0", stayed folded at the top of the list (owner notes A and B).
            header?.let { content ->
                item(key = "header", contentType = "header") {
                    Box(Modifier.fillMaxWidth().heightIn(min = HeaderMinHeight)) { content() }
                }
            }
            items(items = state.rows, key = { it.threadId }, contentType = { "conversation" }) { row ->
                SwipeableConversation(
                    row = row,
                    selected = row.threadId in state.selected,
                    selecting = state.selecting,
                    canArchive = state.archiveAvailable,
                    callbacks = callbacks,
                    startToEnd = archiveAction.takeIf { state.archiveAvailable },
                    endToStart = blockAction.takeIf { !row.isGroup && row.phoneNumber.isNotBlank() },
                    labels = rowLabels[row.threadId].orEmpty(),
                    showCodeChip = row.threadId == codeChipThread,
                    modifier = if (reduceMotion) {
                        Modifier.animateItem(fadeInSpec = null, placementSpec = null, fadeOutSpec = null)
                    } else {
                        Modifier.animateItem()
                    },
                )
            }
            footer?.let { content -> item(key = "footer", contentType = "footer") { content() } }
        }
        FastScrollThumb(listState = listState, rows = state.rows, modifier = Modifier.align(Alignment.CenterEnd))
    }
}


/** The header item's floor; one dp of background keeps it a real, measurable first item. */
private val HeaderMinHeight = 1.dp

// ---- Fast scroll ---------------------------------------------------------------------------------------

private const val FAST_SCROLL_MIN_ROWS = 40
private const val THUMB_HIDE_DELAY_MS = 1200L

/**
 * Draggable thumb for long lists (hundreds of threads). It only reads list state, so it costs nothing per
 * row, and shows the title initial of the row it points at while dragging.
 */
@Composable
private fun FastScrollThumb(listState: LazyListState, rows: List<ConversationRow>, modifier: Modifier = Modifier) {
    if (rows.size < FAST_SCROLL_MIN_ROWS) return
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var trackHeight by remember { mutableIntStateOf(0) }
    var dragging by remember { mutableStateOf(false) }
    var recentlyScrolled by remember { mutableStateOf(false) }
    val scrolling = listState.isScrollInProgress
    LaunchedEffect(scrolling, dragging) {
        if (scrolling || dragging) {
            recentlyScrolled = true
        } else {
            delay(THUMB_HIDE_DELAY_MS)
            recentlyScrolled = false
        }
    }
    val fraction by remember(rows.size) {
        derivedStateOf {
            val total = listState.layoutInfo.totalItemsCount
            if (total <= 1) 0f else listState.firstVisibleItemIndex.toFloat() / (total - 1).coerceAtLeast(1)
        }
    }
    val thumbHeight = 56.dp
    val thumbPx = with(density) { thumbHeight.toPx() }
    val color = MaterialTheme.colorScheme.primary
    val label = stringResource(R.string.convlist_scroll_handle)
    var dragFraction by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(48.dp)
            .onSizeChanged { trackHeight = it.height },
    ) {
        AnimatedVisibility(
            visible = recentlyScrolled,
            enter = fadeIn(tween(120)),
            exit = fadeOut(tween(300)),
        ) {
            val travel = (trackHeight - thumbPx).coerceAtLeast(0f)
            Box(
                modifier = Modifier
                    .offset { IntOffset(0, (travel * fraction).roundToInt()) }
                    .size(width = 48.dp, height = thumbHeight)
                    .semantics { contentDescription = label }
                    .pointerInput(rows.size, trackHeight) {
                        detectVerticalDragGestures(
                            onDragStart = {
                                dragging = true
                                dragFraction = fraction
                            },
                            onDragEnd = { dragging = false },
                            onDragCancel = { dragging = false },
                        ) { change, dragAmount ->
                            change.consume()
                            val usable = (trackHeight - thumbPx).coerceAtLeast(1f)
                            dragFraction = (dragFraction + dragAmount / usable).coerceIn(0f, 1f)
                            val total = listState.layoutInfo.totalItemsCount
                            scope.launch { listState.scrollToItem(((total - 1) * dragFraction).roundToInt()) }
                        }
                    },
                contentAlignment = Alignment.CenterEnd,
            ) {
                Box(
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .size(width = 6.dp, height = 40.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (dragging) color else color.copy(alpha = 0.55f)),
                )
            }
        }
    }
}

/**
 * B15: whether the list jumps back to its first row: the newest conversation changed (a message was sent or received,
 * or the list was just shown) while the user was at the top. A user scrolled down stays where they are.
 */
internal fun shouldReturnToTop(previousTop: Long?, currentTop: Long?, userWasAtTop: Boolean): Boolean =
    userWasAtTop && currentTop != null && previousTop != currentTop

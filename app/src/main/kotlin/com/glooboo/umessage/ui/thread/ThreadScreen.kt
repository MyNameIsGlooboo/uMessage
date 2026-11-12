package com.glooboo.umessage.ui.thread

import com.glooboo.umessage.ui.components.InfoChip
import com.glooboo.umessage.ui.components.chromeInsets
import com.glooboo.umessage.ui.components.topBarInsets
import com.glooboo.umessage.ui.components.isolateNumbers
import com.glooboo.umessage.ui.components.displayTitle
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.ui.conversations.ContactAvatar
import com.glooboo.umessage.ui.icons.ShortcutIcons
import com.glooboo.umessage.ui.icons.Symbols
import com.glooboo.umessage.ui.labels.LabelPickerDialog
import com.glooboo.umessage.helpers.TextSize
import com.glooboo.umessage.helpers.canReadAloud
import com.glooboo.umessage.helpers.threadPrefs
import com.glooboo.umessage.helpers.w3aPrefs
import com.glooboo.umessage.helpers.w3cPrefs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.fossify.commons.extensions.copyToClipboard

private const val PREFETCH_THRESHOLD = 45
private const val SCROLL_TO_BOTTOM_FAB_LIMIT = 6
private const val NEAR_BOTTOM_ROWS = 3
private const val LAYOUT_WAIT_MS = 1_000L
private const val ZOOM_LABEL_MS = 900L

/** I26: room kept above the "Unread messages" line when it is scrolled near the top, in pixels. */
private const val UNREAD_TOP_GAP_PX = 200
private val TitleAvatarSize = 40.dp

/** Opens links, but shows a message instead of crashing when no app can handle them. */
private class SafeUriHandler(private val context: Context, private val delegate: UriHandler) : UriHandler {
    override fun openUri(uri: String) {
        try {
            delegate.openUri(uri)
        } catch (_: Exception) {
            Toast.makeText(context, R.string.thread_no_app, Toast.LENGTH_SHORT).show()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadScreen(vm: ThreadViewModel, actions: ThreadActions) {
    val context = LocalContext.current
    val delegate = LocalUriHandler.current
    // TH-05: a link tap opens the link sheet (domain, full address, Copy, Share, Open), never the browser at once.
    val linkHandler = remember(vm) { linkSheetUriHandler { vm.pendingLink = it } }
    vm.labelsFor?.let { LabelPickerDialog(listOf(it), onDismiss = { vm.labelsFor = null }) }
    vm.notes.editing?.let { NoteEditDialog(vm.notes, it) }
    DeliveryDetailsHost(vm.deliveryDetailsFor, vm.items, onClose = { vm.deliveryDetailsFor = null })
    vm.pendingLink?.let { uri ->
        LinkSheet(
            uri = uri,
            trusted = vm.linksTrusted,
            onOpen = {
                vm.pendingLink = null
                SafeUriHandler(context, delegate).openUri(uri)
            },
            onCopy = {
                vm.pendingLink = null
                context.copyToClipboard(linkCopyText(uri))
            },
            onShare = {
                vm.pendingLink = null
                shareLink(context, uri)
            },
            onDismiss = { vm.pendingLink = null },
            lockRisky = context.threadPrefs.riskyLinksLocked,
        )
    }
    BackHandler(enabled = vm.isSelecting) { vm.clearSelection() }
    BackHandler(enabled = vm.showPeople && !vm.isSelecting) { vm.showPeople = false }
    BackHandler(enabled = vm.searchOpen && !vm.isSelecting) { vm.closeSearch() }

    // The bar tints while messages scroll under it, as on the other screens.
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    CompositionLocalProvider(LocalUriHandler provides linkHandler) {
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
            topBar = {
                Column {
                    when {
                        vm.isSelecting -> SelectionTopBar(vm, actions)
                        vm.searchOpen -> SearchTopBar(
                            query = vm.searchQuery,
                            matchCount = vm.searchMatches.size,
                            matchIndex = vm.searchIndex,
                            searchingOlder = vm.searchingOlder,
                            onQueryChange = vm::updateSearch,
                            onClose = vm::closeSearch,
                            onOlder = vm::showOlderMatch,
                            onNewer = vm::showNewerMatch,
                        )
                        else -> ThreadTopBar(vm, actions, scrollBehavior)
                    }
                    AnimatedVisibility(visible = vm.showPeople && !vm.isSelecting) { PeoplePanel(vm, actions) }
                    if (!vm.isSelecting && !vm.searchOpen) UnknownCardHost(vm, actions)
                }
            },
            contentWindowInsets = chromeInsets(),
            bottomBar = { if (!vm.isRecycleBin) ThreadComposer(vm, actions) },
            snackbarHost = { SnackbarHost(vm.snackbar) },
        ) { inner ->
            MessageList(vm, actions, Modifier.padding(inner))
        }
    }
}

@Composable
private fun MessageList(vm: ThreadViewModel, actions: ThreadActions, modifier: Modifier) {
    val rows = vm.rows
    val listState = rememberLazyListState()

    // The newest message sits at index 0 because the list is reversed. Following a new row keeps a reader
    // who was at the bottom at the bottom; someone reading older messages is left where they are.
    val newestKey = rows.firstOrNull()?.key
    var lastNewestKey by remember { mutableStateOf(newestKey) }
    LaunchedEffect(newestKey) {
        if (lastNewestKey != null && newestKey != lastNewestKey && listState.firstVisibleItemIndex <= NEAR_BOTTOM_ROWS) {
            listState.animateScrollToItem(0)
        }
        lastNewestKey = newestKey
    }

    // The tint of a jumped-to bubble lasts about a second and a half.
    LaunchedEffect(vm.flashedMessage) {
        if (vm.flashedMessage != null) {
            delay(ThreadFlash.DURATION_MS)
            vm.flashMessage(null)
        }
    }

    LaunchedEffect(listState, vm) {
        vm.scroll.collect { command ->
            when (command) {
                ScrollCommand.ToBottom -> listState.animateScrollToItem(0)
                ScrollCommand.ToUnread -> {
                    withTimeoutOrNull(LAYOUT_WAIT_MS) {
                        snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it == vm.rows.size }
                    }
                    val index = vm.rows.indexOfFirst { it is ThreadRow.UnreadDivider }
                    val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                    if (index > lastVisible) {
                        // The list is reversed: a negative offset lifts the line from the bottom edge toward the top.
                        val lift = listState.layoutInfo.viewportSize.height - UNREAD_TOP_GAP_PX
                        listState.scrollToItem(index, -lift.coerceAtLeast(0))
                    }
                }
                is ScrollCommand.ToMessage -> {
                    // New items may have just been published; wait until the list has laid them out.
                    withTimeoutOrNull(LAYOUT_WAIT_MS) {
                        snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it == vm.rows.size }
                    }
                    val index = vm.rows.indexOfFirst { (it as? ThreadRow.Bubble)?.message?.key == command.message }
                    if (index >= 0) {
                        // Reversed list: a negative offset lifts the target from the bottom edge to about the middle.
                        listState.animateScrollToItem(index, -(listState.layoutInfo.viewportSize.height / 2))
                        if (command.flash) vm.flashMessage(command.message)
                    }
                }
            }
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: 0) to info.totalItemsCount
        }
            .distinctUntilChanged()
            .filter { (lastVisible, total) -> total > 0 && lastVisible >= total - PREFETCH_THRESHOLD }
            .collect { actions.onLoadOlder() }
    }

    val showScrollToBottom by remember { derivedStateOf { listState.firstVisibleItemIndex > SCROLL_TO_BOTTOM_FAB_LIMIT } }

    // New messages that arrive while the reader is up in the history are counted on the down button. The anchor is
    // the newest row at the moment the reader left the bottom; reaching the bottom again clears it.
    val atBottom by remember { derivedStateOf { listState.firstVisibleItemIndex <= NEAR_BOTTOM_ROWS } }
    var anchorKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(atBottom) { anchorKey = if (atBottom) null else vm.rows.firstOrNull()?.key }
    val newCount = remember(rows, anchorKey) {
        val received = rows.mapNotNullTo(HashSet()) { row ->
            (row as? ThreadRow.Bubble)?.takeIf { it.message.isReceivedMessage() }?.key
        }
        newRowsSince(rows.map { it.key }, anchorKey) { it in received }
    }

    val context = LocalContext.current
    val pinchZoom = remember { context.w3cPrefs.pinchZoomText }
    var zoomedTo by remember { mutableStateOf<TextSize?>(null) }
    LaunchedEffect(zoomedTo) {
        if (zoomedTo != null) {
            delay(ZOOM_LABEL_MS)
            zoomedTo = null
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (rows.isEmpty() && vm.loaded) {
            EmptyThread()
        }
        LazyColumn(
            state = listState,
            reverseLayout = true,
            contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp),
            modifier = Modifier
                .fillMaxSize()
                .pinchToZoomText(enabled = pinchZoom && !vm.isSelecting) { zoomedTo = it },
        ) {
            items(rows.size, key = { rows[it].key }, contentType = { rows[it]::class }) { index ->
                ThreadRowItem(rows[index], vm, actions)
            }
        }
        zoomedTo?.let { PinchZoomLabel(it, Modifier.align(Alignment.Center)) }
        AnimatedVisibility(
            visible = showScrollToBottom || newCount > 0,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        ) {
            val scope = rememberCoroutineScope()
            if (newCount > 0) {
                ExtendedFloatingActionButton(
                    onClick = { scope.launch { listState.animateScrollToItem(0) } },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    icon = { Icon(Symbols.KeyboardArrowDown, contentDescription = null) },
                    text = { Text(pluralStringResource(R.plurals.thread_new_messages_chip, newCount, newCount)) },
                )
            } else {
                SmallFloatingActionButton(
                    onClick = { scope.launch { listState.animateScrollToItem(0) } },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ) {
                    Icon(
                        Symbols.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.thread_scroll_to_latest)
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyThread() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            stringResource(R.string.thread_empty_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(R.string.thread_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThreadTopBar(vm: ThreadViewModel, actions: ThreadActions, scrollBehavior: TopAppBarScrollBehavior) {
    val colors = MaterialTheme.colorScheme
    TopAppBar(
        scrollBehavior = scrollBehavior,
        windowInsets = topBarInsets(),
        title = { ThreadTitle(vm, actions) },
        navigationIcon = {
            IconButton(onClick = actions.onBack) {
                Icon(Symbols.ArrowBack, contentDescription = stringResource(R.string.thread_back))
            }
        },
        actions = {
            val menu = vm.menu
            if (menu.dial) {
                IconButton(onClick = { actions.onMenuAction(ThreadMenuAction.Dial) }) {
                    Icon(Symbols.Call, contentDescription = stringResource(R.string.thread_call))
                }
            }
            ThreadOverflowMenu(menu, actions.onMenuAction)
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = colors.background,
            scrolledContainerColor = colors.background,
            titleContentColor = colors.onBackground,
            navigationIconContentColor = colors.onBackground,
            actionIconContentColor = colors.onBackground,
        ),
    )
}

/**
 * Avatar, name and number, as in Google Messages. Tapping it opens the conversation details when they are
 * available; the avatar is the same one the conversation list draws.
 */
@Composable
private fun ThreadTitle(vm: ThreadViewModel, actions: ThreadActions) {
    val detailsLabel = stringResource(R.string.thread_menu_details)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .then(
                if (vm.menu.details) {
                    Modifier.clickable(onClickLabel = detailsLabel, role = Role.Button) {
                        actions.onMenuAction(ThreadMenuAction.Details)
                    }
                } else {
                    Modifier
                },
            )
            .heightIn(min = 48.dp)
            .padding(end = 8.dp),
    ) {
        if (vm.title.isNotEmpty()) {
            ContactAvatar(title = vm.title, photoUri = vm.avatarPhotoUri, isGroup = vm.isGroup, size = TitleAvatarSize)
            Spacer(Modifier.width(12.dp))
        }
        Column {
            Text(
                isolateNumbers(displayTitle(vm.title)),
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            ThreadMetaRow(
                threadId = vm.threadId,
                subtitle = vm.subtitle?.let { text -> { ThreadSubtitle(text) } },
                // TH-12: a rule decides for this sender.
                status = if (vm.senderStatus == SenderStatus.None) null else ({ ThreadStatusChip(vm.senderStatus) }),
                onLabelsClick = { vm.labelsFor = vm.threadId },
            )
        }
    }
}

@Composable
private fun ThreadSubtitle(text: String) {
    Text(
        isolateNumbers(displayTitle(text)),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun ThreadStatusChip(status: SenderStatus) {
    val colors = MaterialTheme.colorScheme
    when (status) {
        SenderStatus.Allowed -> InfoChip(
            text = stringResource(R.string.w2b2_thread_chip_allowed),
            container = colors.secondaryContainer,
            content = colors.onSecondaryContainer,
        )
        SenderStatus.Blocked -> InfoChip(
            text = stringResource(R.string.w2b2_thread_chip_blocked),
            container = colors.errorContainer,
            content = colors.onErrorContainer,
        )
        SenderStatus.None -> Unit
    }
}

@Composable
private fun OverflowMenu(content: @Composable (close: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Symbols.MoreVert, contentDescription = stringResource(R.string.thread_more_options))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            content { open = false }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(vm: ThreadViewModel, actions: ThreadActions) {
    val colors = MaterialTheme.colorScheme
    val selected = vm.selectedMessages
    val count = vm.selectedIds.size
    val single = count == 1
    val hasText = selected.any { it.body.isNotEmpty() }
    val canSave = selected.isNotEmpty() && selected.all { (it.attachment?.attachments?.size ?: 0) > 0 }
    // I13: a message in the recycle bin or still scheduled is not starred.
    val canStar = selected.isNotEmpty() && !vm.isRecycleBin && selected.none { it.isScheduled }
    val selectionStarred = allStarred(selected, vm.stars.keys)
    // Sender decisions apply to the one sender of a one-person thread, from a message they sent.
    val fromSender = single && !vm.isGroup && selected.firstOrNull()?.isReceivedMessage() == true
    val menu = vm.menu
    val context = LocalContext.current
    // I49: one message with text, and no code while codes are hidden.
    val readAloudOk = single && selected.firstOrNull()?.let {
        canReadAloud(it.body, context.w3aPrefs.hideCodesOnLockScreen, detectCopyTokens(it.body).isNotEmpty())
    } == true
    val readAloudLabel =
        if (readAloudOk && vm.readingAloudId == selected.first().getStableId()) {
            R.string.read_aloud_stop
        } else {
            R.string.read_aloud_action
        }
    TopAppBar(
        windowInsets = topBarInsets(),
        title = {
            // U23: only the number, so the five action icons never cut the title; TalkBack still reads the words.
            val spoken = pluralStringResource(R.plurals.thread_selected_count, count, count)
            Text(
                count.toString(),
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { contentDescription = spoken },
            )
        },
        navigationIcon = {
            IconButton(onClick = vm::clearSelection) {
                Icon(Symbols.Close, contentDescription = stringResource(R.string.thread_clear_selection))
            }
        },
        actions = {
            if (hasText) {
                IconButton(onClick = { actions.onSelectionAction(SelectionAction.Copy) }) {
                    Icon(Symbols.ContentCopy, contentDescription = stringResource(R.string.thread_action_copy))
                }
            }
            if (canStar) {
                val starLabel = if (selectionStarred) R.string.i13_unstar else R.string.i13_star
                IconButton(onClick = { actions.onSelectionAction(SelectionAction.Star) }) {
                    Icon(
                        if (selectionStarred) ShortcutIcons.StarFilled else ShortcutIcons.Star,
                        contentDescription = stringResource(starLabel),
                    )
                }
            }
            if (single) {
                // TH-11: a one-off forward, named apart from the automatic forward rules.
                IconButton(onClick = { actions.onSelectionAction(SelectionAction.Forward) }) {
                    Icon(Symbols.Forward, contentDescription = stringResource(R.string.thread_r2_forward_message))
                }
            }
            // All action buttons are the same 48dp IconButtons side by side, so the spacing is even.
            IconButton(onClick = { actions.onSelectionAction(SelectionAction.Delete) }) {
                Icon(Symbols.Delete, contentDescription = stringResource(R.string.thread_action_delete))
            }
            OverflowMenu { close ->
                // TH-11, TH-12: what uMessage does with this sender first, then the message itself.
                val senderEntries = listOf(
                    Triple(menu.allowSender, R.string.w2b2_thread_menu_allow, ThreadMenuAction.AllowSender),
                    Triple(menu.reportSpam, R.string.thread_menu_report_spam, ThreadMenuAction.ReportSpam),
                    Triple(menu.block, R.string.thread_menu_block, ThreadMenuAction.Block),
                    Triple(
                        menu.alwaysForward,
                        R.string.thread_r2_menu_always_forward,
                        ThreadMenuAction.AlwaysForward,
                    ),
                    Triple(menu.whyDecided, R.string.w2b2_thread_menu_why, ThreadMenuAction.WhyDecided),
                ).filter { fromSender && it.first }
                senderEntries.forEach { (_, label, action) ->
                    DropdownMenuItem(
                        text = { Text(stringResource(label)) },
                        leadingIcon = { Icon(selectionMenuIcon(action), contentDescription = null) },
                        onClick = {
                            close()
                            vm.clearSelection()
                            actions.onMenuAction(action)
                        },
                    )
                }
                if (senderEntries.isNotEmpty()) HorizontalDivider()
                val entries = selectionEntries(
                    hasText, single, canSave, readAloudOk, readAloudLabel, vm.isRecycleBin,
                    noteLabel = noteMenuLabel(selected, vm.notes.byKey),
                )
                SelectionMenuRows(entries, actions, onSelectAll = vm::selectAll, close = close)
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = colors.primaryContainer,
            titleContentColor = colors.onPrimaryContainer,
            navigationIconContentColor = colors.onPrimaryContainer,
            actionIconContentColor = colors.onPrimaryContainer,
        ),
    )
}

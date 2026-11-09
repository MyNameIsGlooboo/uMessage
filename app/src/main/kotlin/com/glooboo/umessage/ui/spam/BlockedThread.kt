package com.glooboo.umessage.ui.spam

import android.content.Context
import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.glooboo.umessage.R
import com.glooboo.umessage.helpers.DateStyle
import com.glooboo.umessage.helpers.w3cPrefs
import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.spam.data.BlockedNotifier
import com.glooboo.umessage.ui.components.EmptyState
import com.glooboo.umessage.ui.components.UMessageTopBar
import com.glooboo.umessage.ui.components.chromeInsets
import com.glooboo.umessage.ui.components.displayNumber
import com.glooboo.umessage.ui.components.horizontalChromePadding
import com.glooboo.umessage.ui.components.isolateNumbers
import com.glooboo.umessage.ui.components.rememberBubbleMaxWidth
import com.glooboo.umessage.ui.components.jalaliListDate
import com.glooboo.umessage.ui.components.localizeDigits
import com.glooboo.umessage.ui.components.uiLocale
import com.glooboo.umessage.ui.icons.Symbols
import com.glooboo.umessage.ui.theme.UMessageTheme
import com.glooboo.umessage.ui.theme.contentDirection
import com.glooboo.umessage.ui.thread.BubbleGroup
import com.glooboo.umessage.ui.thread.bubbleShape
import com.glooboo.umessage.ui.thread.timeOnlyLabel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One row of the sender thread: a day header or a message bubble. */
sealed interface BlockedThreadItem {
    val key: String

    data class Day(val day: LocalDate) : BlockedThreadItem {
        override val key: String get() = "day-${day.toEpochDay()}"
    }

    data class Bubble(val message: BlockedMessage) : BlockedThreadItem {
        override val key: String get() = "msg-${message.id}"
    }
}

/**
 * The rows of the sender thread for a list drawn bottom-up (newest at the bottom, as in a chat): [messages] newest
 * first, each followed by its day's header when the next (older) message is from another day, so the header shows
 * above that day's messages.
 */
internal fun blockedThreadItems(
    messages: List<BlockedMessage>,
    zone: ZoneId = ZoneId.systemDefault(),
): List<BlockedThreadItem> {
    fun dayOf(message: BlockedMessage) = Instant.ofEpochMilli(message.receivedAt).atZone(zone).toLocalDate()
    val sorted = messages.sortedByDescending { it.receivedAt }
    return buildList {
        sorted.forEachIndexed { index, message ->
            add(BlockedThreadItem.Bubble(message))
            val day = dayOf(message)
            val older = sorted.getOrNull(index + 1)
            if (older == null || dayOf(older) != day) add(BlockedThreadItem.Day(day))
        }
    }
}

@Immutable
class BlockedThreadActions(
    val onBack: () -> Unit = {},
    /** "Not spam" for every message of the sender: moves them, allows the sender, and opens the normal chat. */
    val onMoveToInbox: () -> Unit = {},
    /** Deletes every message of the sender, with Undo. */
    val onDeleteAll: () -> Unit = {},
    /** Puts one message in the inbox without allowing the sender (ID-16), with Undo. */
    val onReleaseOnce: (Long) -> Unit = {},
    val onDeleteMessage: (Long) -> Unit = {},
    val onOpenRules: () -> Unit = {},
)

/**
 * Stateful entry point of the sender thread. It has its own [BlockedViewModel] that ignores the tab's chip, so it
 * shows and acts on every held message of [address]. After the last message has left (moved, released or deleted)
 * and its Undo snackbar has ended without Undo, the screen closes itself; while the snackbar shows it stays open,
 * so the Undo is never lost with the screen. Rotation keeps the model, and [BlockedEvents] shows a pending Undo again.
 */
// The route hands its view model on to the shared snackbar events, which collect it themselves.
@Suppress("ViewModelForwarding")
@Composable
fun BlockedThreadRoute(
    address: String,
    onBack: () -> Unit,
    onOpenRules: () -> Unit,
    viewModel: BlockedViewModel = viewModel(factory = BlockedViewModel.ThreadFactory),
    threadIdOf: suspend (Context, String) -> Long = BlockedChat::threadIdOf,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var closeWhenEmpty by rememberSaveable { mutableStateOf(false) }
    var seen by rememberSaveable { mutableStateOf(false) }
    // "Not spam" is working or has finished and the chat is about to open: the buttons wait, the rows stay.
    var moving by rememberSaveable { mutableStateOf(false) }
    BlockedEvents(viewModel, snackbar, onSettle = { committed -> closeWhenEmpty = committed })
    OpenChatAfterNotSpam(viewModel, address, threadIdOf, onBack, onRetry = { moving = false })
    val hasMessages = state.groups.any { it.hasAddress(address) }
    LaunchedEffect(hasMessages) { if (hasMessages) seen = true }
    // I5: opening the thread answers the notifications of its messages.
    val appContext = LocalContext.current.applicationContext
    val heldIds = state.groups.firstOrNull { it.hasAddress(address) }?.messages?.map { it.id }.orEmpty()
    LaunchedEffect(heldIds) { BlockedNotifier.cancel(appContext, heldIds) }
    // Opening the thread reads it; a message that arrives while it is open is read too (the effect runs again).
    val unreadHeld = state.groups.firstOrNull { it.hasAddress(address) }?.unreadCount ?: 0
    LaunchedEffect(address, unreadHeld) { if (unreadHeld > 0) viewModel.markRead(address) }
    // A last snackbar (a failure) is let finish before the screen goes. While the chat opens it never closes here.
    val settled = !state.loading && !hasMessages && !state.hasPendingUndo && !moving &&
        snackbar.currentSnackbarData == null
    // Nothing (left) to show: either the sender's messages were already gone when the screen opened (purged, or
    // restored from another screen), or the user moved or deleted them and kept that.
    val close by rememberUpdatedState(onBack)
    LaunchedEffect(settled, closeWhenEmpty, seen) {
        if (settled && (closeWhenEmpty || !seen)) close()
    }
    val actions = remember(viewModel, address, onBack, onOpenRules) {
        BlockedThreadActions(
            onBack = onBack,
            onMoveToInbox = {
                if (!moving) moving = viewModel.notSpam(setOf(address), openChat = true)
            },
            onDeleteAll = { viewModel.delete(setOf(address)) },
            onReleaseOnce = viewModel::releaseOnce,
            onDeleteMessage = { id -> viewModel.deleteMessages(setOf(id)) },
            onOpenRules = onOpenRules,
        )
    }
    BlockedThreadScreen(address, state, snackbar, actions, moving = moving)
}

/**
 * A blocked sender's messages as a thread (owner notes r3): incoming bubbles under day headers, newest at the
 * bottom, and no compose box. The bar at the bottom says the messages are held and offers Move to inbox; Delete is
 * in the top bar. Each message has its own menu (tap or long press): Release once, Copy, Copy code, Why blocked,
 * Delete. Links are not made tappable: these texts are held as possible spam or phishing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockedThreadScreen(
    address: String,
    state: BlockedUiState,
    snackbarHostState: SnackbarHostState,
    actions: BlockedThreadActions,
    modifier: Modifier = Modifier,
    /** "Not spam" is working: the button shows progress and waits, and the rows stay until the chat opens. */
    moving: Boolean = false,
) {
    val group = state.groups.firstOrNull { it.hasAddress(address) }
    val shown = isolateNumbers(displayNumber(address))
    var whyId by rememberSaveable { mutableStateOf<Long?>(null) }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = {
            UMessageTopBar(title = shown, onBack = actions.onBack, scrollBehavior = scrollBehavior)
        },
        contentWindowInsets = chromeInsets(),
        bottomBar = { if (group != null) HeldFooter(shown, actions, moving) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { inner ->
        val content = Modifier.fillMaxSize().padding(inner)
        when {
            state.loading -> Box(content, contentAlignment = Alignment.Center) {
                val label = stringResource(R.string.spam_loading)
                CircularProgressIndicator(Modifier.semantics { contentDescription = label })
            }
            group == null && moving -> Box(content, contentAlignment = Alignment.Center) {
                val label = stringResource(R.string.nsf_moving)
                CircularProgressIndicator(Modifier.semantics { contentDescription = label })
            }
            group == null -> Box(content, contentAlignment = Alignment.Center) {
                EmptyState(
                    iconRes = R.drawable.ic_spam_shield_check,
                    title = stringResource(R.string.r3b_thread_empty_title),
                    body = stringResource(R.string.r3b_thread_empty_body),
                )
            }
            else -> {
                val rows = remember(group.messages) { blockedThreadItems(group.messages) }
                val heldLines = remember(group.messages) { heldLineShownFor(group.messages) }
                LazyColumn(
                    modifier = content,
                    reverseLayout = true,
                    contentPadding = PaddingValues(top = 8.dp, bottom = 12.dp),
                ) {
                    items(rows, key = { it.key }) { item ->
                        when (item) {
                            is BlockedThreadItem.Day -> ThreadDayHeader(item.day)
                            is BlockedThreadItem.Bubble -> HeldBubble(
                                message = item.message,
                                showHeldLine = item.message.id in heldLines,
                                simLabel = simLabel(state.sims, item.message.subscriptionId),
                                actions = actions,
                                onWhy = { whyId = item.message.id },
                            )
                        }
                    }
                }
            }
        }
    }
    whyId?.let { id ->
        val message = group?.messages?.firstOrNull { it.id == id }
        if (message == null) {
            // The message left (released, deleted or purged) while its dialog was up.
            if (!state.loading) LaunchedEffect(id) { whyId = null }
        } else {
            WhyBlockedDialog(message, state, actions.onOpenRules, onDismiss = { whyId = null })
        }
    }
}

/**
 * Where a normal thread has its compose box: what this place is, and the two ways out of it, both with a visible
 * label (U06): "Not spam" moves the sender to the inbox, "Delete" removes every message.
 */
@Composable
private fun HeldFooter(shown: String, actions: BlockedThreadActions, moving: Boolean) {
    val colors = MaterialTheme.colorScheme
    val deleteAll = stringResource(R.string.r3b_thread_delete_all, shown)
    Surface(color = colors.surfaceContainer) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .horizontalChromePadding()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                stringResource(R.string.r3b_thread_note),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            ) {
                TextButton(
                    onClick = actions.onDeleteAll,
                    enabled = !moving,
                    modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = deleteAll },
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.error),
                ) { Text(stringResource(R.string.ux8c_delete), maxLines = 1) }
                FilledTonalButton(
                    onClick = actions.onMoveToInbox,
                    enabled = !moving,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    if (moving) {
                        val label = stringResource(R.string.nsf_moving)
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 8.dp).size(20.dp)
                                .semantics { contentDescription = label },
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(Symbols.Inbox, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    }
                    Text(stringResource(R.string.ux8c_not_spam), maxLines = 1)
                }
            }
        }
    }
}

/** "Today", "Yesterday", then the weekday and date in the user's date style (Jalali when chosen). */
@Composable
private fun ThreadDayHeader(day: LocalDate) {
    val context = LocalContext.current
    val locale = uiLocale()
    val today = LocalDate.now()
    val todayText = stringResource(R.string.e_day_today)
    val yesterdayText = stringResource(R.string.e_day_yesterday)
    val label = remember(day, today, locale, todayText) {
        when (day) {
            today -> todayText
            today.minusDays(1) -> yesterdayText
            else -> localizeDigits(blockedDayText(context, day, today), locale)
        }
    }
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 6.dp)
            .semantics { heading() },
    )
}

/** A date header older than yesterday: weekday and date, or the Jalali date for that date style. */
private fun blockedDayText(context: Context, day: LocalDate, today: LocalDate): String {
    if (context.w3cPrefs.dateStyle == DateStyle.JALALI) {
        return jalaliListDate(day, today, persian = context.resources.configuration.locales[0].language == "fa")
    }
    val millis = day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val year = if (day.year == today.year) DateUtils.FORMAT_NO_YEAR else DateUtils.FORMAT_SHOW_YEAR
    return DateUtils.formatDateTime(
        context,
        millis,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_ALL or year,
    )
}

/**
 * One held message as an incoming bubble, with its time (and SIM on a dual-SIM phone) under it. Tap or long press
 * opens its menu.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HeldBubble(
    message: BlockedMessage,
    showHeldLine: Boolean,
    simLabel: String?,
    actions: BlockedThreadActions,
    onWhy: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val resources = LocalResources.current
    val locale = uiLocale()
    var menuOpen by remember { mutableStateOf(false) }
    val dark = colors.background.luminance() < 0.5f
    val maxWidth = rememberBubbleMaxWidth()
    val time = remember(message.receivedAt, locale) {
        timeOnlyLabel(message.receivedAt, ZoneId.systemDefault(), DateFormat.is24HourFormat(context), locale)
    }
    val caption = listOfNotNull(time, simLabel).joinToString(stringResource(R.string.ui_separator))
    val body = blockedBodyText(resources, message)
    val actionsLabel = stringResource(R.string.r3b_message_actions)
    Column(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        if (showHeldLine) {
            Text(
                stringResource(heldLineText(message)),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp, bottom = 2.dp),
            )
        }
        Box {
            val shape = bubbleShape(sent = false, group = BubbleGroup.Single)
            Surface(
                shape = shape,
                color = colors.surfaceContainerHighest,
                contentColor = colors.onSurface,
                // An incoming bubble gets a hairline against a light page, as in a normal thread (TH-08).
                border = if (dark) null else BorderStroke(1.dp, colors.outlineVariant),
                modifier = Modifier
                    .widthIn(max = maxWidth)
                    .clip(shape)
                    .semantics(mergeDescendants = true) { contentDescription = "$body, $caption" }
                    .combinedClickable(
                        onClickLabel = actionsLabel,
                        onLongClickLabel = actionsLabel,
                        onClick = { menuOpen = true },
                        onLongClick = { menuOpen = true },
                    ),
            ) {
                Column {
                    MmsThumbnail(message, Modifier.padding(6.dp))
                    if (body.isNotEmpty()) {
                        Text(
                            body,
                            style = MaterialTheme.typography.bodyLarge.contentDirection(),
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                }
            }
            HeldMessageMenu(
                message = message,
                expanded = menuOpen,
                onDismiss = { menuOpen = false },
                actions = actions,
                onWhy = onWhy,
            )
        }
        Row(
            modifier = Modifier.padding(start = 8.dp, top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(caption, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            if (message.isMms && message.hasText) MmsMediaChip(message)
        }
    }
}

/** Release once, Copy text, Copy code, Why blocked, then Delete apart in the error color (BR-07). */
@Composable
private fun HeldMessageMenu(
    message: BlockedMessage,
    expanded: Boolean,
    onDismiss: () -> Unit,
    actions: BlockedThreadActions,
    onWhy: () -> Unit,
) {
    val context = LocalContext.current
    val code = remember(message.id, message.body, message.reason) { BlockedExplain.of(message)?.code }
    val textCopied = stringResource(R.string.r3b_text_copied)
    val codeCopied = stringResource(R.string.w3b_code_copied)
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (message.canRestoreContent) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.w3b_release_once)) },
                leadingIcon = { Icon(Symbols.Inbox, contentDescription = null) },
                onClick = { onDismiss(); actions.onReleaseOnce(message.id) },
            )
        }
        if (message.hasText) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.r3b_copy_text)) },
                leadingIcon = { Icon(Symbols.ContentCopy, contentDescription = null) },
                onClick = { onDismiss(); copyToClipboard(context, message.body, textCopied) },
            )
        }
        code?.let {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.w3b_copy_code)) },
                leadingIcon = { Icon(Symbols.ContentCopy, contentDescription = null) },
                onClick = { onDismiss(); copyToClipboard(context, it, codeCopied) },
            )
        }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.r3b_why_blocked)) },
            leadingIcon = { Icon(Symbols.Info, contentDescription = null) },
            onClick = { onDismiss(); onWhy() },
        )
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text(stringResource(R.string.r3b_delete_message)) },
            leadingIcon = { Icon(Symbols.Delete, contentDescription = null) },
            colors = MenuDefaults.itemColors(
                textColor = MaterialTheme.colorScheme.error,
                leadingIconColor = MaterialTheme.colorScheme.error,
            ),
            onClick = { onDismiss(); actions.onDeleteMessage(message.id) },
        )
    }
}

/** Copies from the Blocked tab as sensitive (I25). The app never clears the clipboard by itself. */
private fun copyToClipboard(context: Context, text: String, copiedText: String) {
    com.glooboo.umessage.helpers.SensitiveClip.mark(context, text)
    // Android 13+ confirms copies itself.
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
        android.widget.Toast.makeText(context, copiedText, android.widget.Toast.LENGTH_SHORT).show()
    }
}


// region Previews

@Preview(name = "Blocked thread", showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun BlockedThreadPreview() = UMessageTheme {
    val messages = SampleData.blocked()
    val address = messages.first().address
    BlockedThreadScreen(
        address = address,
        state = BlockedUiState(loading = false, groups = groupBySender(messages)),
        snackbarHostState = remember { SnackbarHostState() },
        actions = BlockedThreadActions(),
    )
}

// endregion

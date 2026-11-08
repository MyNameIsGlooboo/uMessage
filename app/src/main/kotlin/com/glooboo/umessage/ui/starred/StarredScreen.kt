package com.glooboo.umessage.ui.starred

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.helpers.StarredItem
import com.glooboo.umessage.helpers.StarredKind
import com.glooboo.umessage.helpers.StarredList
import com.glooboo.umessage.helpers.StarredPage
import com.glooboo.umessage.models.MessageKey
import com.glooboo.umessage.models.SearchResult
import com.glooboo.umessage.ui.components.EmptyState
import com.glooboo.umessage.ui.components.LoadingState
import com.glooboo.umessage.ui.components.SelectionTopBar
import com.glooboo.umessage.ui.components.UMessageScaffold
import com.glooboo.umessage.ui.components.UMessageTopBar
import com.glooboo.umessage.ui.components.formatListTimeStyled
import com.glooboo.umessage.ui.conversations.ContactAvatar
import com.glooboo.umessage.ui.icons.ShortcutIcons
import com.glooboo.umessage.ui.icons.Symbols
import com.glooboo.umessage.ui.main.StarredSnapshotDialog
import com.glooboo.umessage.ui.theme.contentDirection
import com.glooboo.umessage.ui.theme.starColor

/**
 * The starred messages themselves: one row per message, so two stars from one sender are two rows. A tap opens the
 * chat at the message (a message whose chat was deleted opens its saved text instead); a long press selects rows, and
 * the selection bar unstars them. The search icon filters the rows by text or chat name.
 */
@Composable
fun StarredScreen(
    page: StarredPage?,
    selected: Set<MessageKey>,
    actions: StarredActions,
    modifier: Modifier = Modifier,
) {
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var snapshot by remember { mutableStateOf<StarredItem?>(null) }
    val selecting = selected.isNotEmpty()
    UMessageScaffold(
        modifier = modifier,
        topBar = {
            if (selecting) {
                StarredSelectionBar(selected, actions)
            } else {
                UMessageTopBar(
                    title = stringResource(R.string.i13_filter_starred),
                    onBack = actions.onBack,
                    actions = {
                        IconButton(onClick = {
                            searching = !searching
                            if (!searching) query = ""
                        }) {
                            Icon(Symbols.Search, contentDescription = stringResource(R.string.i44_starred_search_hint))
                        }
                    },
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            if (searching && !selecting) StarredSearchField(query) { query = it }
            val shown = remember(page, query) { page?.let { StarredList.filter(it.items, query) }.orEmpty() }
            StarredBody(
                page = page,
                shown = shown,
                filtering = query.isNotBlank(),
                selected = selected,
                bottom = padding.calculateBottomPadding(),
                actions = StarredRowActions(actions, onSnapshot = { snapshot = it }),
            )
        }
    }
    snapshot?.let { item ->
        StarredSnapshotDialog(
            hit = SearchResult(item.id, item.title, item.text, "", item.threadId, item.photoUri, item.isMMS),
            onUnstar = { actions.onUnstar(setOf(item.key)) },
            onDismiss = { snapshot = null },
        )
    }
}

@Composable
private fun StarredSelectionBar(selected: Set<MessageKey>, actions: StarredActions) {
    SelectionTopBar(count = selected.size, onClose = actions.onClearSelection) {
        IconButton(onClick = actions.onSelectAll) {
            Icon(Symbols.SelectAll, contentDescription = stringResource(R.string.screens_select_all))
        }
        IconButton(onClick = { actions.onUnstar(selected) }) {
            Icon(ShortcutIcons.StarFilled, contentDescription = stringResource(R.string.i13_unstar))
        }
    }
}

@Composable
private fun StarredSearchField(query: String, onChange: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    TextField(
        value = query,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
        singleLine = true,
        placeholder = {
            Text(stringResource(R.string.i44_starred_search_hint), maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onChange("") }) {
                    Icon(Symbols.Close, contentDescription = stringResource(R.string.main_search_clear))
                }
            }
        },
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
    )
}

/** The taps of a row; a message whose chat is gone cannot be opened, so it shows its saved text. */
private class StarredRowActions(val screen: StarredActions, val onSnapshot: (StarredItem) -> Unit)

@Composable
private fun StarredBody(
    page: StarredPage?,
    shown: List<StarredItem>,
    filtering: Boolean,
    selected: Set<MessageKey>,
    bottom: Dp,
    actions: StarredRowActions,
) {
    when {
        page == null -> LoadingState()
        page.items.isEmpty() -> EmptyState(
            icon = ShortcutIcons.Star,
            title = stringResource(R.string.i13_filter_starred_empty_title),
            body = stringResource(R.string.i13_filter_starred_empty_body),
        )
        shown.isEmpty() && filtering && !page.hasMore -> EmptyState(
            icon = Symbols.Search,
            title = stringResource(R.string.i44_starred_no_match),
            body = "",
        )
        else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottom)) {
            items(shown, key = { it.listKey }) { item ->
                StarredRow(item, item.key in selected, selected.isNotEmpty(), actions)
            }
            if (page.hasMore) {
                item(key = "more") {
                    TextButton(
                        onClick = actions.screen.onShowMore,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    ) { Text(stringResource(R.string.search_page_show_more)) }
                }
            }
            item(key = "starred-note") {
                Text(
                    stringResource(R.string.i13_starred_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StarredRow(item: StarredItem, selected: Boolean, selecting: Boolean, actions: StarredRowActions) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val date = remember(item.dateMillis) { formatListTimeStyled(context, item.dateMillis) }
    val open = {
        when {
            selecting -> actions.screen.onToggle(item)
            item.chatGone -> actions.onSnapshot(item)
            else -> actions.screen.onOpen(item)
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) colors.secondaryContainer else Color.Transparent)
            .combinedClickable(onClick = { open() }, onLongClick = { if (!selecting) actions.screen.onToggle(item) })
            .heightIn(min = 72.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ContactAvatar(title = item.title, photoUri = item.photoUri, isGroup = item.isGroup, selected = selected)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(date, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, maxLines = 1)
            }
            StarredText(item)
            StarredMarks(item)
        }
    }
}

@Composable
private fun StarredText(item: StarredItem) {
    val colors = MaterialTheme.colorScheme
    val label = when (item.kind) {
        StarredKind.PICTURE -> stringResource(R.string.i28_hit_picture)
        StarredKind.ATTACHMENT -> stringResource(R.string.attachment)
        StarredKind.TEXT -> null
    }
    Column {
        if (label != null) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = colors.primary, maxLines = 1)
        }
        if (item.text.isNotBlank()) {
            Text(
                item.text,
                style = MaterialTheme.typography.bodyMedium.contentDirection(),
                color = colors.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The star every row has, and the note mark of a message with a private note. */
@Composable
private fun StarredMarks(item: StarredItem) {
    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(
            ShortcutIcons.StarFilled,
            contentDescription = stringResource(R.string.i13_starred_mark),
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.starColor,
        )
        if (item.hasNote) {
            Icon(
                Symbols.Description,
                contentDescription = stringResource(R.string.has_notes),
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

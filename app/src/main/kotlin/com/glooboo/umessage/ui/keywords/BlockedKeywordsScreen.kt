package com.glooboo.umessage.ui.keywords
import androidx.compose.runtime.saveable.rememberSaveable
import com.glooboo.umessage.ui.components.StringSetStateSaver

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.ui.components.EmptyState
import com.glooboo.umessage.ui.components.FabListBottomPadding
import com.glooboo.umessage.ui.components.LoadingState
import com.glooboo.umessage.ui.components.SelectionTopBar
import com.glooboo.umessage.ui.components.UMessageScaffold
import com.glooboo.umessage.ui.components.UMessageTopBar
import com.glooboo.umessage.ui.icons.Symbols

/**
 * The old blocked words that could not become rules. Read-only apart from deleting: words are never added here (new
 * blocking goes into the uMessage rules). Long-press selects, the row menu copies or deletes, the bar menu exports.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BlockedKeywordsScreen(
    keywords: List<String>,
    loaded: Boolean,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onCopy: (String) -> Unit,
    onDelete: (Set<String>) -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selected by rememberSaveable(saver = StringSetStateSaver) { mutableStateOf(emptySet()) }
    var query by rememberSaveable { mutableStateOf("") }
    val shownKeywords = remember(keywords, query) {
        val needle = query.trim()
        if (needle.isEmpty()) keywords else keywords.filter { it.contains(needle, ignoreCase = true) }
    }
    val selection = remember(selected, keywords) { selected.filterTo(HashSet()) { it in keywords } }
    val selecting = selection.isNotEmpty()
    BackHandler(enabled = selecting) { selected = emptySet() }

    UMessageScaffold(
        modifier = modifier,
        snackbarHostState = snackbarHostState,
        topBar = {
            KeywordsTopBar(
                selection = selection,
                onClearSelection = { selected = emptySet() },
                onBack = onBack,
                onCopy = onCopy,
                onDelete = onDelete,
                onExport = onExport,
            )
        },
    ) { padding ->
        when {
            !loaded -> LoadingState(Modifier.padding(top = padding.calculateTopPadding()))
            keywords.isEmpty() -> Box(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
                EmptyState(
                    icon = Symbols.Block,
                    title = stringResource(R.string.screens_keywords_empty_title),
                    body = stringResource(R.string.screens_keywords_empty_body),
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            else -> Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
                if (keywords.size >= SEARCH_MIN_KEYWORDS || query.isNotEmpty()) {
                    KeywordSearch(query = query, onQueryChange = { query = it })
                }
                if (shownKeywords.isEmpty()) {
                    EmptyState(
                        icon = Symbols.Search,
                        title = stringResource(R.string.w2a_keywords_no_match_title),
                        body = stringResource(R.string.w2a_keywords_no_match_body),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        // Room so the last row can scroll clear of the FAB and the navigation bar.
                        contentPadding = PaddingValues(
                            bottom = padding.calculateBottomPadding() + FabListBottomPadding,
                        ),
                    ) {
                        items(shownKeywords, key = { it }) { keyword ->
                            val isSelected = keyword in selection
                            KeywordRow(
                                keyword = keyword,
                                selected = isSelected,
                                onClick = {
                                    if (selecting) {
                                        selected = if (isSelected) selection - keyword else selection + keyword
                                    }
                                },
                                onLongClick = { selected = selection + keyword },
                                onCopy = { onCopy(keyword) },
                                onDelete = { onDelete(setOf(keyword)) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The selection bar while keywords are selected, else the title bar with the export menu. */
@Composable
private fun KeywordsTopBar(
    selection: Set<String>,
    onClearSelection: () -> Unit,
    onBack: () -> Unit,
    onCopy: (String) -> Unit,
    onDelete: (Set<String>) -> Unit,
    onExport: () -> Unit,
) {
    if (selection.isNotEmpty()) {
        SelectionTopBar(count = selection.size, onClose = onClearSelection) {
            if (selection.size == 1) {
                IconButton(onClick = {
                    onCopy(selection.first())
                    onClearSelection()
                }) {
                    Icon(Symbols.ContentCopy, contentDescription = stringResource(R.string.screens_copy))
                }
            }
            IconButton(onClick = {
                onDelete(selection)
                onClearSelection()
            }) {
                Icon(Symbols.Delete, contentDescription = stringResource(R.string.screens_delete))
            }
        }
    } else {
        var menuOpen by remember { mutableStateOf(false) }
        UMessageTopBar(title = stringResource(R.string.blocked_keywords), onBack = onBack) {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Symbols.MoreVert, contentDescription = stringResource(R.string.screens_more_options))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.screens_keywords_menu_export)) },
                        leadingIcon = { Icon(Symbols.Upload, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onExport()
                        },
                    )
                }
            }
        }
    }
}

/** From this many keywords on, the list gets a search box. */
private const val SEARCH_MIN_KEYWORDS = 8

/** The same filled, rounded search field as the rules list. */
@Composable
private fun KeywordSearch(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        placeholder = { Text(stringResource(R.string.w2a_keywords_search_hint)) },
        leadingIcon = { Icon(Symbols.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Symbols.Close, contentDescription = stringResource(R.string.w2a_keywords_search_clear))
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedBorderColor = Color.Transparent,
            unfocusedBorderColor = Color.Transparent,
        ),
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun KeywordRow(
    keyword: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.background)
            .heightIn(min = 56.dp)
            .combinedClickable(role = Role.Button, onClick = onClick, onLongClick = onLongClick)
            .semantics { this.selected = selected }
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            keyword,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(vertical = 8.dp),
        )
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Symbols.MoreVert, contentDescription = stringResource(R.string.screens_more_options))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.screens_copy)) },
                    leadingIcon = { Icon(Symbols.ContentCopy, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onCopy()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.screens_delete)) },
                    leadingIcon = { Icon(Symbols.Delete, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    },
                )
            }
        }
    }
}

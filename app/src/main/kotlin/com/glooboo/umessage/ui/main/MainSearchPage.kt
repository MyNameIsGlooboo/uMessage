package com.glooboo.umessage.ui.main

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.helpers.HiddenChats
import com.glooboo.umessage.helpers.HiddenChatsSession
import com.glooboo.umessage.helpers.SearchHistoryPrefs
import com.glooboo.umessage.models.SearchResult
import com.glooboo.umessage.ui.conversations.ConversationRow
import com.glooboo.umessage.ui.icons.Symbols
import com.glooboo.umessage.ui.labels.activeLabelName
import com.glooboo.umessage.ui.spam.BlockedThreadActivity

/** The most the filter chip in the field may take, so a long label name leaves room for the typed text. */
private val FieldChipMaxWidth = 168.dp

/**
 * N11: the full-screen search page. A text field with a back arrow on top; below it the tiles (with the labels and the
 * last searches) until a tile is picked or text is typed, then the matching conversations, one row each. Picking a tile
 * lists its conversations at once, hides the keyboard and shows the tile as a removable chip in the field (U21), in
 * place of the tile grid. [rows] are the inbox's conversations, which the tiles filter;
 * [shortcut] is what the Unknown, Starred, Images, Links, label and SIM tiles found. Back from the results returns to
 * the tiles; [onLeave] closes the page, and the caller also hides the keyboard and parks focus there, so the inbox
 * below does not hand it to its first button.
 */
@Composable
internal fun MainSearchPage(
    search: MainSearchState,
    rows: List<ConversationRow>,
    shortcut: ShortcutHits,
    onQueryChange: (String) -> Unit,
    onOpenResult: (SearchResult) -> Unit,
    onLeave: () -> Unit,
    modifier: Modifier = Modifier,
    simTiles: List<SimTile> = emptyList(),
    onOpenStars: () -> Unit = {},
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val fieldFocus = remember { FocusRequester() }
    val context = LocalContext.current
    // CL-14: the last few searches, kept on the phone, come back as chips on the empty search page.
    val history = remember(context) { SearchHistoryPrefs.get(context) }
    var recent by remember { mutableStateOf(history.recentSearches) }
    // A search typed while hidden chats are unlocked may be about them; the chips outlive the unlock, so skip it then.
    val hiddenChats = remember(context) { HiddenChats.get(context) }
    val saveRecent = { query: String ->
        val hiddenOpen = hiddenChats.isLockOn() && HiddenChatsSession.isUnlocked() &&
            hiddenChats.chosenIds().isNotEmpty()
        if (isSearchable(query) && !hiddenOpen) recent = history.remember(query.trim())
    }
    val resultsListState = rememberLazyListState()
    val goBack = { if (search.hasTileOn()) search.backToTiles(onQueryChange) else onLeave() }
    LaunchedEffect(Unit) { fieldFocus.requestFocus() }

    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            TextField(
                value = search.query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth().focusRequester(fieldFocus),
                singleLine = true,
                placeholder = {
                    Text(stringResource(R.string.main_search_hint), maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                leadingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = goBack) {
                            Icon(Symbols.ArrowBack, contentDescription = stringResource(R.string.main_search_back))
                        }
                        if (search.filter != InboxFilter.All) {
                            ActiveFilterChip(
                                search.filter,
                                onClear = { search.filter = InboxFilter.All },
                                modifier = Modifier.widthIn(max = FieldChipMaxWidth),
                                labelName = activeLabelName(search)
                                    ?: simTiles.firstOrNull { it.subscriptionId == search.simId }?.label
                                        .takeIf { search.filter == InboxFilter.Sim },
                            )
                        }
                    }
                },
                trailingIcon = {
                    if (search.query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Symbols.Close, contentDescription = stringResource(R.string.main_search_clear))
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    saveRecent(search.query)
                    keyboard?.hide()
                }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )
            val actions = SearchPanelActions(
                onPickRecent = onQueryChange,
                onClearRecent = {
                    history.recentSearches = emptyList()
                    recent = emptyList()
                },
                onFilter = {
                    // Starred is its own screen: one row per starred message, not the chats that hold them.
                    if (it == InboxFilter.Starred) {
                        onOpenStars()
                    } else {
                        search.filter = it
                        if (it != InboxFilter.All) keyboard?.hide()
                    }
                },
                onSim = {
                    search.toggleSim(it)
                    if (search.filter == InboxFilter.Sim) keyboard?.hide()
                },
                onOpen = { result ->
                    saveRecent(search.query)
                    onOpenResult(result)
                },
                onOpenBlocked = { address ->
                    saveRecent(search.query)
                    // The thread asks for the app lock itself when it covers the Blocked screens.
                    context.startActivity(BlockedThreadActivity.intent(context, address))
                },
            )
            SearchResultsPanel(search, rows, shortcut, resultsListState, recent, simTiles, actions)
        }
    }
}

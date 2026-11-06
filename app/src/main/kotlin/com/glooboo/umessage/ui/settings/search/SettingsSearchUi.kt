package com.glooboo.umessage.ui.settings.search

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.ui.components.TonalRows
import com.glooboo.umessage.ui.icons.Symbols
import com.glooboo.umessage.ui.settings.search.SettingsIndexEntry.Needs

private const val RESULT_SUMMARY_LINES = 2

/** The search icon of the Settings top bar. */
@Composable
fun SettingsSearchButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(Symbols.Search, contentDescription = stringResource(R.string.settings_search_open))
    }
}

/** The top bar while searching: back, the field (focused at once) and a clear button, like the inbox search. */
@Composable
fun SettingsSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focus.requestFocus() }
    Surface(modifier, color = MaterialTheme.colorScheme.background) {
        TextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth().statusBarsPadding().focusRequester(focus),
            singleLine = true,
            placeholder = {
                Text(stringResource(R.string.settings_search_hint), maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            leadingIcon = {
                IconButton(onClick = onClose) {
                    Icon(Symbols.ArrowBack, contentDescription = stringResource(R.string.main_search_back))
                }
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Symbols.Close, contentDescription = stringResource(R.string.main_search_clear))
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
        )
    }
}

/** The body while searching: up to [SettingsSearchMatcher.MAX_RESULTS] results, a hint, or a short "nothing found". */
@Composable
fun SettingsSearchResults(
    query: String,
    available: Set<Needs>,
    onOpen: (SettingsIndexEntry) -> Unit,
    modifier: Modifier = Modifier,
    forwarding: Boolean = true,
) {
    val resources = LocalResources.current
    val configuration = LocalConfiguration.current
    val search = remember(resources, configuration, available, forwarding) {
        SettingsSearch(resources, available, forwarding)
    }
    val hits = remember(search, query) { search.find(query) }
    when {
        query.isBlank() -> SearchMessage(R.string.settings_search_start)
        hits.isEmpty() -> SearchMessage(R.string.settings_search_none)
        else -> TonalRows(modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
            hits.forEach { hit -> SearchResultRow(hit, onClick = { onOpen(hit.entry) }) }
        }
    }
}

@Composable
private fun SearchMessage(@StringRes text: Int) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
    )
}

/** Title, the summary line and the name of the page the setting lives on. */
@Composable
private fun SearchResultRow(hit: SettingsSearch.Hit, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(hit.title, style = MaterialTheme.typography.bodyLarge)
            if (hit.summary.isNotEmpty()) {
                Text(
                    hit.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = RESULT_SUMMARY_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                hit.page,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

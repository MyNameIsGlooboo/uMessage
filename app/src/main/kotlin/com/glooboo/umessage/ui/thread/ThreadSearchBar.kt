package com.glooboo.umessage.ui.thread

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.ui.components.topBarInsets
import com.glooboo.umessage.ui.icons.Symbols

/**
 * TH-20: "Search in conversation" in place of the top bar: the field (focused on open), "2 of 5", and buttons for
 * the older and newer match. [matchIndex] counts from the newest match.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SearchTopBar(
    query: String,
    matchCount: Int,
    matchIndex: Int,
    searchingOlder: Boolean,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
    onOlder: () -> Unit,
    onNewer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    val keyboard = LocalSoftwareKeyboardController.current
    TopAppBar(
        modifier = modifier,
        windowInsets = topBarInsets(),
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Symbols.ArrowBack, contentDescription = stringResource(R.string.thread_r2_search_close))
            }
        },
        title = {
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.titleMedium.copy(color = colors.onSurface),
                cursorBrush = SolidColor(colors.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                // Search: the keyboard gives way to the results.
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                decorationBox = { field ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text(
                                stringResource(R.string.thread_r2_search_hint),
                                style = MaterialTheme.typography.titleMedium,
                                color = colors.onSurfaceVariant,
                            )
                        }
                        field()
                    }
                },
            )
        },
        actions = {
            if (query.isNotBlank()) {
                if (searchingOlder && matchCount > 0) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                }
                Text(
                    if (matchCount == 0) {
                        stringResource(
                            if (searchingOlder) R.string.thread_search_older_busy else R.string.thread_r2_search_none,
                        )
                    } else {
                        stringResource(R.string.thread_r2_search_count, matchIndex + 1, matchCount)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            IconButton(onClick = onOlder, enabled = matchCount > 1) {
                Icon(Symbols.ExpandLess, contentDescription = stringResource(R.string.thread_r2_search_older))
            }
            IconButton(onClick = onNewer, enabled = matchCount > 1) {
                Icon(Symbols.ExpandMore, contentDescription = stringResource(R.string.thread_r2_search_newer))
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.surfaceContainerHigh),
    )
}

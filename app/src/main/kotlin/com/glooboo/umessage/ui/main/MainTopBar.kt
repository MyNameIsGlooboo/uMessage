package com.glooboo.umessage.ui.main

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.ui.icons.Symbols
import com.glooboo.umessage.ui.theme.isDarkBackground

/** The logo's charcoal and cream: the mark is drawn in charcoal on a light page and in cream on a dark one. */
private const val CHARCOAL_ARGB = 0xFF2A2526L
private const val CREAM_ARGB = 0xFFF5DDBEL
private val MarkCharcoal = Color(CHARCOAL_ARGB)
private val MarkCream = Color(CREAM_ARGB)

/**
 * N11: the inbox's top bar: the mark, the app name, the search icon and the overflow menu with the [menu]
 * entries (Archived, Recycle bin, Scheduled, Starred and, last, Settings).
 */
@Composable
internal fun MainTopBar(
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    menu: List<InboxMenuItem> = listOf(InboxMenuItem.Settings),
    onMenu: (InboxMenuItem) -> Unit = {},
    recycleBinCount: Int = 0,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .heightIn(min = 64.dp)
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painterResource(R.drawable.ic_logo_mark),
            contentDescription = null,
            modifier = Modifier.size(28.dp),
            tint = if (colors.isDarkBackground) MarkCream else MarkCharcoal,
        )
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleLarge,
            color = colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 12.dp).semantics { heading() },
        )
        IconButton(onClick = onSearch) {
            Icon(Symbols.Search, contentDescription = stringResource(R.string.main_search_hint))
        }
        InboxOverflowMenu(menu, recycleBinCount, onMenu)
    }
}

/** The one chip that says which filter is on; tapping it turns the filter off. */
@Composable
internal fun ActiveFilterChip(
    filter: InboxFilter,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    /** I29: the name of the label that is the filter. */
    labelName: String? = null,
) {
    val tile = filterTile(filter)
    val label = if (tile != null) stringResource(tile.labelRes) else labelName ?: return
    val description = stringResource(R.string.n11_filter_remove, label)
    InputChip(
        selected = true,
        onClick = onClear,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingIcon = { Icon(Symbols.Close, contentDescription = null, modifier = Modifier.size(18.dp)) },
        modifier = modifier.minimumInteractiveComponentSize().semantics { contentDescription = description },
    )
}


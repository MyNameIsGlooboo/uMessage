package com.glooboo.umessage.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.glooboo.umessage.R
import com.glooboo.umessage.ui.icons.Symbols

/** One row of a main tab's overflow menu, before it is drawn. */
internal class TabMenuItem(
    val labelRes: Int,
    val icon: ImageVector,
    val onClick: () -> Unit,
    val isSettings: Boolean = false,
)

/**
 * The rows of a main tab's overflow menu: its own rows first, then Settings last whenever [onSettings] is given.
 * With Settings the menu is never empty, so its 3-dot button always shows.
 */
internal fun tabMenuItems(entries: List<TabMenuItem>, onSettings: (() -> Unit)?): List<TabMenuItem> = buildList {
    addAll(entries)
    onSettings?.let { add(TabMenuItem(R.string.main_settings, Symbols.Settings, it, isSettings = true)) }
}

/** The search icon of a main tab's top bar. */
@Composable
internal fun SearchAction(onSearch: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onSearch, modifier = modifier) {
        Icon(Symbols.Search, contentDescription = stringResource(R.string.main_search_hint))
    }
}

/** A tab's 3-dot menu; a divider sets its Settings row apart from the tab's own rows. */
@Composable
internal fun TabOverflowMenu(items: List<TabMenuItem>, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        IconButton(onClick = { open = true }) {
            Icon(Symbols.MoreVert, contentDescription = stringResource(R.string.nav_folder_more))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items.forEachIndexed { index, item ->
                if (item.isSettings && index > 0) HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(item.labelRes)) },
                    leadingIcon = { Icon(item.icon, contentDescription = null) },
                    onClick = {
                        open = false
                        item.onClick()
                    },
                )
            }
        }
    }
}

package com.glooboo.umessage.ui.main

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.glooboo.umessage.R
import com.glooboo.umessage.extensions.config
import com.glooboo.umessage.ui.icons.Symbols

/** U04: the one overflow menu of the inbox's top bar ([InboxMenuPolicy] decides the entries). */
@Composable
internal fun InboxOverflowMenu(
    entries: List<InboxMenuItem>,
    onPick: (InboxMenuItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        IconButton(onClick = { open = true }) {
            Icon(Symbols.MoreVert, contentDescription = stringResource(R.string.screens_more_options))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            entries.forEach { item ->
                if (item == InboxMenuItem.Settings && item != entries.first()) HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(menuLabel(item))) },
                    leadingIcon = { Icon(menuIcon(item), contentDescription = null) },
                    onClick = {
                        open = false
                        onPick(item)
                    },
                )
            }
        }
    }
}

private fun menuIcon(item: InboxMenuItem): ImageVector = when (item) {
    InboxMenuItem.Archived -> Symbols.Archive
    InboxMenuItem.RecycleBin -> Symbols.RestoreFromTrash
    InboxMenuItem.Scheduled -> Symbols.ScheduleSend
    InboxMenuItem.Starred -> Symbols.Keep
    InboxMenuItem.Settings -> Symbols.Settings
}

private fun menuLabel(item: InboxMenuItem): Int = when (item) {
    InboxMenuItem.Archived -> R.string.main_tab_archived
    InboxMenuItem.RecycleBin -> R.string.ux8a_menu_recycle_bin
    InboxMenuItem.Scheduled -> R.string.scheduled_list_title
    InboxMenuItem.Starred -> R.string.i13_filter_starred
    InboxMenuItem.Settings -> R.string.main_settings
}

/** Whether the recycle bin is switched on, read again each time the screen resumes (Settings can change it). */
@Composable
internal fun rememberRecycleBinOn(): Boolean {
    val context = LocalContext.current
    var on by remember { mutableStateOf(context.config.useRecycleBin) }
    LifecycleResumeEffect(Unit) {
        on = context.config.useRecycleBin
        onPauseOrDispose { }
    }
    return on
}

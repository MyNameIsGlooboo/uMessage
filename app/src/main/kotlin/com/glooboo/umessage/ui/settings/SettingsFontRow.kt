package com.glooboo.umessage.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.glooboo.umessage.R
import com.glooboo.umessage.helpers.AppFont
import com.glooboo.umessage.helpers.w3cPrefs
import com.glooboo.umessage.ui.components.ChoiceDialog
import com.glooboo.umessage.ui.components.SettingsRow
import com.glooboo.umessage.ui.icons.Symbols

@Composable
private fun AppFont.label(): String = stringResource(
    when (this) {
        AppFont.UMESSAGE -> R.string.font_umessage
        AppFont.SYSTEM -> R.string.font_system
    },
)

/** "Font": one row that opens a dialog; the app follows the choice at once. Reads and writes its own preference. */
@Composable
fun AppFontRow(modifier: Modifier = Modifier) {
    val prefs = LocalContext.current.w3cPrefs
    var font by remember { mutableStateOf(prefs.appFont) }
    var picking by remember { mutableStateOf(false) }
    SettingsRow(
        title = stringResource(R.string.font_title),
        value = font.label(),
        icon = Symbols.TextFields,
        onClick = { picking = true },
        showChevron = true,
        modifier = modifier,
    )
    if (picking) {
        val fonts = AppFont.entries
        ChoiceDialog(
            title = stringResource(R.string.font_title),
            options = fonts.map { it.label() },
            selectedIndex = fonts.indexOf(font),
            onSelect = {
                picking = false
                font = fonts[it]
                prefs.appFont = fonts[it]
            },
            onDismiss = { picking = false },
        )
    }
}

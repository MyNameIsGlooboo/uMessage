package com.glooboo.umessage.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import com.glooboo.umessage.R
import com.glooboo.umessage.helpers.BankCardPrefs
import com.glooboo.umessage.ui.components.SettingsSwitchRow
import com.glooboo.umessage.ui.icons.Symbols

/** The bank alert cards switch (I31) of the Typing group; it reads and writes [BankCardPrefs]. */
@Composable
internal fun BankCardSettingsRow() {
    if (LocalInspectionMode.current) return
    val prefs = BankCardPrefs.get(LocalContext.current)
    var enabled by remember { mutableStateOf(prefs.cardsEnabled) }
    SettingsSwitchRow(
        title = stringResource(R.string.w7d_bank_cards_title),
        summary = stringResource(R.string.w7d_bank_cards_summary),
        icon = Symbols.Description,
        checked = enabled,
        onCheckedChange = {
            enabled = it
            prefs.cardsEnabled = it
        },
    )
}

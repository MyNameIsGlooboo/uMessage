package com.glooboo.umessage.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.glooboo.umessage.R
import com.glooboo.umessage.helpers.DateStyle
import com.glooboo.umessage.helpers.threadPrefs
import com.glooboo.umessage.helpers.w3cPrefs
import com.glooboo.umessage.ui.components.ChoiceDialog
import com.glooboo.umessage.ui.components.SettingsRow
import com.glooboo.umessage.ui.components.SettingsSwitchRow
import com.glooboo.umessage.ui.components.TonalRows
import com.glooboo.umessage.ui.icons.Symbols

@Composable
private fun DateStyle.label(): String = stringResource(
    when (this) {
        DateStyle.GREGORIAN -> R.string.w3c_date_gregorian
        DateStyle.JALALI -> R.string.w3c_date_jalali
        DateStyle.BOTH -> R.string.w3c_date_both
    },
)

/**
 * U25, U46, U05: the look settings that are not colours, on the Appearance page: text size, the calendar (one row that
 * opens a dialog, like Language) and unread first. They read and write [com.glooboo.umessage.helpers.W3cPrefs]
 * themselves.
 */
@Composable
fun AppearanceDisplayRows(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val prefs = remember(context) { context.w3cPrefs }
    var textSize by remember { mutableStateOf(prefs.textSize) }
    var dateStyle by remember { mutableStateOf(prefs.dateStyle) }
    var unreadFirst by remember { mutableStateOf(prefs.unreadFirst) }
    var picking by remember { mutableStateOf(false) }
    Column(modifier) {
        TonalRows {
            TextSizeRow(
                current = textSize,
                onChange = {
                    textSize = it
                    prefs.textSize = it
                },
            )
            AppFontRow()
            SettingsRow(
                title = stringResource(R.string.ux8e_calendar),
                value = dateStyle.label(),
                icon = Symbols.CalendarMonth,
                onClick = { picking = true },
                showChevron = true,
            )
            SettingsSwitchRow(
                title = stringResource(R.string.i36_unread_first),
                summary = stringResource(R.string.i36_unread_first_body),
                icon = Symbols.Inbox,
                checked = unreadFirst,
                onCheckedChange = {
                    unreadFirst = it
                    prefs.unreadFirst = it
                },
            )
        }
        if (picking) {
            val styles = DateStyle.entries
            ChoiceDialog(
                title = stringResource(R.string.ux8e_calendar),
                options = styles.map { it.label() },
                selectedIndex = styles.indexOf(dateStyle),
                onSelect = {
                    picking = false
                    dateStyle = styles[it]
                    prefs.dateStyle = styles[it]
                    // The thread's scheduled-message label still reads the older switch: keep both in step.
                    context.threadPrefs.useJalaliDates = styles[it] != DateStyle.GREGORIAN
                },
                onDismiss = { picking = false },
            )
        }
    }
}

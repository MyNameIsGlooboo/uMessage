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
import com.glooboo.umessage.helpers.smartreply.smartReplyPrefs
import com.glooboo.umessage.ui.components.SettingsSwitchRow
import com.glooboo.umessage.ui.icons.Symbols

/** The smart replies switches (I59) of the Typing group: in conversations, and as buttons in notifications. */
@Composable
internal fun SmartReplySettingsRow() {
    if (LocalInspectionMode.current) return
    val prefs = LocalContext.current.smartReplyPrefs
    var enabled by remember { mutableStateOf(prefs.enabled) }
    var inNotifications by remember { mutableStateOf(prefs.notificationsEnabled) }
    SettingsSwitchRow(
        title = stringResource(R.string.smart_replies_title),
        summary = stringResource(R.string.smart_replies_summary),
        icon = Symbols.Tune,
        checked = enabled,
        onCheckedChange = {
            enabled = it
            prefs.enabled = it
        },
    )
    SettingsSwitchRow(
        title = stringResource(R.string.smart_replies_notif_title),
        summary = stringResource(R.string.smart_replies_notif_summary),
        icon = Symbols.Tune,
        checked = inNotifications,
        onCheckedChange = {
            inNotifications = it
            prefs.notificationsEnabled = it
        },
    )
}

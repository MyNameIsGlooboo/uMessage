package com.glooboo.umessage.ui.settings

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.glooboo.umessage.R
import com.glooboo.umessage.helpers.BubbleSupport
import com.glooboo.umessage.helpers.Oem
import com.glooboo.umessage.helpers.OemHelper
import com.glooboo.umessage.helpers.notifyPrefs
import com.glooboo.umessage.ui.components.SettingsRow
import com.glooboo.umessage.ui.components.SettingsSwitchRow
import com.glooboo.umessage.ui.icons.Symbols

/**
 * I50, I53, I27: the three optional notification switches of Settings > Notifications, all off by default, and the
 * "Copy the code when I tap the notification" switch, on by default. They read and write
 * [com.glooboo.umessage.helpers.NotifyPrefs] themselves. Chat bubbles are hidden below Android 11 and shown
 * as a plain row with the reason while the app lock is on ([appLockOn]); the live-notification switch is only shown
 * on Xiaomi phones.
 */
@Composable
fun NotifyExtraRows(appLockOn: Boolean) {
    val prefs = LocalContext.current.notifyPrefs
    var copyOnTap by remember { mutableStateOf(prefs.copyCodeOnTap) }
    SettingsSwitchRow(
        title = stringResource(R.string.otp_tap_copy_title),
        icon = Symbols.ContentCopy,
        checked = copyOnTap,
        onCheckedChange = {
            copyOnTap = it
            prefs.copyCodeOnTap = it
        },
        info = stringResource(R.string.otp_tap_copy_info),
    )
    var wake by remember { mutableStateOf(prefs.wakeScreen) }
    SettingsSwitchRow(
        title = stringResource(R.string.wake_screen_title),
        summary = stringResource(R.string.wake_screen_summary),
        icon = Symbols.Smartphone,
        checked = wake,
        onCheckedChange = {
            wake = it
            prefs.wakeScreen = it
        },
    )
    if (BubbleSupport.supported(Build.VERSION.SDK_INT)) {
        if (appLockOn) {
            SettingsRow(
                title = stringResource(R.string.bubbles_title),
                summary = stringResource(R.string.bubbles_locked),
                icon = Symbols.ChatBubble,
            )
        } else {
            var bubbles by remember { mutableStateOf(prefs.chatBubbles) }
            SettingsSwitchRow(
                title = stringResource(R.string.bubbles_title),
                summary = stringResource(R.string.bubbles_summary),
                icon = Symbols.ChatBubble,
                checked = bubbles,
                onCheckedChange = {
                    bubbles = it
                    prefs.chatBubbles = it
                },
            )
        }
    }
    if (OemHelper.detect() == Oem.XIAOMI) {
        var focus by remember { mutableStateOf(prefs.focusCodes) }
        SettingsSwitchRow(
            title = stringResource(R.string.focus_codes_title),
            summary = stringResource(R.string.focus_codes_summary),
            icon = Symbols.Code,
            checked = focus,
            onCheckedChange = {
                focus = it
                prefs.focusCodes = it
            },
        )
    }
}

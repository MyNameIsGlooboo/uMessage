package com.glooboo.umessage.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.ui.components.ChoiceDialog
import com.glooboo.umessage.ui.components.SettingsGroup
import com.glooboo.umessage.ui.components.SettingsRow
import com.glooboo.umessage.ui.components.SettingsSwitchRow
import com.glooboo.umessage.ui.components.TonalRows
import com.glooboo.umessage.ui.icons.Symbols
import org.fossify.commons.R as CommonsR

/**
 * U05, U22: everything about notifications on one page: Android's channel settings (SE-10: marked as leaving the app),
 * the code alert channel (ID-26), the blocked-message alert, the weekly summary and the optional switches; then the
 * lock screen: what shows there and whether codes are hidden (M1).
 */
@Composable
internal fun NotificationsPage(state: SettingsUiState, actions: SettingsActions) {
    SettingsGroup(stringResource(R.string.settings_nav_group_alerts)) {
        SettingsRow(
            title = stringResource(CommonsR.string.customize_notifications),
            summary = stringResource(R.string.settings_nav_open_android_settings),
            icon = Symbols.Notifications,
            onClick = actions.onCustomizeNotifications,
            trailing = { OpensOutsideIcon() },
        )
        SettingsRow(
            title = stringResource(R.string.settings_f_code_alert),
            summary = stringResource(R.string.settings_f_code_alert_body),
            icon = Symbols.NotificationsActive,
            onClick = actions.onCodeAlert,
            trailing = { OpensOutsideIcon() },
        )
        val spam = state.spam
        if (spam != null) {
            SettingsSwitchRow(
                title = stringResource(R.string.spam_notify_title),
                summary = stringResource(R.string.settings_nav_notify_body),
                icon = Symbols.Block,
                checked = spam.notifyOnBlock,
                onCheckedChange = actions.onNotifyOnBlock,
            )
        }
        WeeklyDigestRow()
        NotifyExtraRows(appLockOn = state.appLock)
    }
    SettingsGroup(stringResource(R.string.ux8e_group_lock_screen)) {
        SettingsRow(
            title = stringResource(R.string.settings_nav_lock_screen),
            // N7: the value goes under the title; beside it, "Sender and message" squeezed the title onto three
            // lines at Large text.
            summary = state.lockScreenLabel,
            icon = Symbols.Lock,
            onClick = actions.onLockScreen,
            showChevron = true,
        )
        SettingsSwitchRow(
            title = stringResource(R.string.w3d_hide_lock_codes),
            summary = stringResource(R.string.settings_f_hide_lock_codes_body),
            icon = Symbols.Visibility,
            checked = state.hideCodesOnLockScreen,
            onCheckedChange = actions.onHideCodesOnLockScreen,
        )
    }
}

/** SE-10: the row opens a screen of Android, not of the app. */
@Composable
private fun OpensOutsideIcon() {
    Icon(SettingsIcons.OpenInNew, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** U16: typing and sending on one page, in two groups. Storage has its own page. */
@Composable
internal fun TypingPage(state: SettingsUiState, actions: SettingsActions) {
    SettingsGroup(stringResource(R.string.settings_nav_group_typing)) {
        SettingsSwitchRow(
            title = stringResource(R.string.ui_settings_character_counter),
            icon = Symbols.TextFields,
            checked = state.showCharacterCounter,
            onCheckedChange = actions.onCharacterCounter,
        )
        SettingsSwitchRow(
            title = stringResource(R.string.settings_nav_simple_chars),
            summary = stringResource(R.string.settings_nav_simple_chars_body),
            icon = Symbols.Backspace,
            checked = state.useSimpleCharacters,
            onCheckedChange = actions.onSimpleCharacters,
        )
        SettingsSwitchRow(
            title = stringResource(R.string.ui_settings_send_on_enter),
            icon = Symbols.Send,
            checked = state.sendOnEnter,
            onCheckedChange = actions.onSendOnEnter,
        )
        ComposerTypingRows()
        SmartReplySettingsRow()
        BankCardSettingsRow()
    }
    SettingsGroup(stringResource(R.string.settings_nav_group_sending)) {
        SettingsSwitchRow(
            title = stringResource(R.string.ui_settings_delivery_reports),
            icon = Symbols.DoneAll,
            checked = state.deliveryReports,
            onCheckedChange = actions.onDeliveryReports,
        )
        SettingsSwitchRow(
            title = stringResource(R.string.settings_nav_long_mms),
            summary = stringResource(R.string.settings_long_mms_body),
            icon = Symbols.Sms,
            checked = state.sendLongAsMms,
            onCheckedChange = actions.onLongAsMms,
        )
        SettingsSwitchRow(
            title = stringResource(R.string.settings_nav_group_mms),
            summary = stringResource(R.string.settings_group_mms_body),
            icon = Symbols.Group,
            checked = state.sendGroupAsMms,
            onCheckedChange = actions.onGroupAsMms,
        )
        SettingsRow(
            title = stringResource(R.string.settings_nav_mms_size),
            value = state.mmsLimitLabel,
            icon = Symbols.Image,
            onClick = actions.onMmsLimit,
            showChevron = true,
        )
        ComposerSendingRows()
    }
}

/**
 * U07, U16, U26: the recycle bin switch is the first row (on for new installs), its page and the automatic cleanup
 * show only while it is on, and the archive comes last.
 */
@Composable
internal fun StoragePage(state: SettingsUiState, actions: SettingsActions) {
    SettingsGroup(stringResource(R.string.ux8e_group_recycle)) {
        SettingsSwitchRow(
            title = stringResource(R.string.ux8e_keep_deleted),
            summary = stringResource(R.string.ux8e_keep_deleted_body),
            icon = Symbols.Delete,
            checked = state.useRecycleBin,
            onCheckedChange = actions.onRecycleBin,
        )
        if (state.useRecycleBin) {
            SettingsRow(
                title = stringResource(R.string.ux8e_recycle_bin),
                summary = state.recycleBinLabel,
                icon = Symbols.RestoreFromTrash,
                onClick = actions.onOpenRecycleBin,
                showChevron = true,
            )
        }
    }
    if (state.useRecycleBin) {
        SettingsGroup(stringResource(R.string.ux8e_group_cleanup)) {
            StorageCleanupRows(state.useRecycleBin, actions.onRecycleBin)
        }
    }
    SettingsGroup(stringResource(R.string.ux8e_group_archive)) {
        val openArchived = actions.onOpenArchived
        if (state.archivedCount != null && openArchived != null) {
            SettingsRow(
                title = stringResource(R.string.settings_nav_archived),
                value = state.archivedCount.toString(),
                icon = Symbols.Archive,
                onClick = openArchived,
                showChevron = true,
            )
        }
        SettingsSwitchRow(
            title = stringResource(R.string.settings_nav_keep_archived),
            summary = stringResource(R.string.settings_nav_keep_archived_body),
            icon = Symbols.Inbox,
            checked = state.keepArchived,
            onCheckedChange = actions.onKeepArchived,
        )
    }
}

/** What a backup or a restore covers: the messages, or the rules and settings. Each is its own file. */
private enum class BackupKind { BACKUP, RESTORE }

/**
 * U09: two verbs for two jobs. "Back up" and "Restore" each ask what: messages, or rules and settings. A settings
 * restore shows a summary to confirm first (the host asks for the app lock before that).
 */
@Composable
internal fun BackupPage(actions: SettingsActions) {
    var asking by rememberSaveable { mutableStateOf<BackupKind?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TonalRows(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
            SettingsRow(
                title = stringResource(R.string.ux8e_backup_row),
                summary = stringResource(R.string.ux8e_backup_row_body),
                icon = Symbols.Upload,
                onClick = { asking = BackupKind.BACKUP },
            )
            SettingsRow(
                title = stringResource(R.string.ux8e_restore_row),
                summary = stringResource(R.string.ux8e_restore_row_body),
                icon = Symbols.Download,
                onClick = { asking = BackupKind.RESTORE },
            )
            SettingsRow(
                title = stringResource(R.string.sim_messages_title),
                summary = stringResource(R.string.sim_messages_row_body),
                icon = Symbols.SimCard,
                onClick = actions.onSimMessages,
            )
        }
        Text(
            stringResource(R.string.ux8e_backup_warning),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        asking?.let { kind ->
            BackupKindDialog(kind, actions, onClose = { asking = null })
        }
    }
}

@Composable
private fun BackupKindDialog(kind: BackupKind, actions: SettingsActions, onClose: () -> Unit) {
    val backingUp = kind == BackupKind.BACKUP
    ChoiceDialog(
        title = stringResource(if (backingUp) R.string.ux8e_backup_what else R.string.ux8e_restore_what),
        options = listOf(stringResource(R.string.ux8e_what_messages), stringResource(R.string.ux8e_what_settings)),
        selectedIndex = -1,
        onSelect = { index ->
            onClose()
            val messages = index == 0
            when {
                backingUp && messages -> actions.onExport()
                backingUp -> actions.onBackupSettings()
                messages -> actions.onImport()
                else -> actions.onRestoreSettings()
            }
        },
        onDismiss = onClose,
    )
}

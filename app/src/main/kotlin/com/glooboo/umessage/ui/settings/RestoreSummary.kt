package com.glooboo.umessage.ui.settings

import android.content.Context
import androidx.annotation.StringRes
import com.glooboo.umessage.R
import com.glooboo.umessage.backup.BackupSettings
import com.glooboo.umessage.backup.SettingsBackup

/** B3 / ID-38: what a settings restore would do, shown for the owner to confirm before anything changes. */
object RestoreSummary {

    /** The setting each backup key changes, by its row title. Keys missing here (one-time flags) are not shown. */
    private val labels: Map<String, Int> = mapOf(
        BackupSettings.SPAM_MODE to R.string.settings_nav_spam_row,
        BackupSettings.PURGE_DAYS to R.string.settings_nav_purge_row,
        BackupSettings.NOTIFY_ON_BLOCK to R.string.spam_notify_title,
        BackupSettings.SHOW_FORWARDED_TAB to R.string.settings_nav_forwarded_tab,
        BackupSettings.JALALI to R.string.w2b2_settings_group_dates,
        BackupSettings.DATE_STYLE to R.string.w2b2_settings_group_dates,
        BackupSettings.MASK_CODES to R.string.w3d_mask_codes,
        BackupSettings.MASK_KEEP_LAST to R.string.w3d_mask_keep_last,
        BackupSettings.HIDE_LOCK_CODES to R.string.w3d_hide_lock_codes,
        BackupSettings.DAILY_LIMIT to R.string.w3d_daily_limit,
        BackupSettings.TEXT_SIZE to R.string.w3c_text_size,
        BackupSettings.THEME to R.string.settings_f_appearance,
        BackupSettings.SEND_ON_ENTER to R.string.ui_settings_send_on_enter,
        BackupSettings.LOCK_SCREEN to R.string.settings_nav_lock_screen,
        BackupSettings.FORWARD_PAUSED to R.string.settings_nav_forward_pause,
    ) + moreLabels()

    /** The settings added later are named by the group they sit in, so the list stays short. */
    private fun moreLabels(): Map<String, Int> {
        val groups = mapOf(
            R.string.n09_label_spam to listOf(
                BackupSettings.UNKNOWN_NOTIFY, BackupSettings.OPEN_LINK, BackupSettings.OUTSIDE_REGION,
                BackupSettings.CARRIER_NUMBER,
            ),
            R.string.n09_label_notifications to listOf(
                BackupSettings.WAKE_SCREEN, BackupSettings.CHAT_BUBBLES, BackupSettings.FOCUS_CODES,
                BackupSettings.CHAT_SOUNDS, BackupSettings.COPY_CODE_ON_TAP,
            ),
            R.string.n09_label_chat to listOf(
                BackupSettings.UNREAD_FIRST, BackupSettings.PINCH_ZOOM, BackupSettings.TAPBACKS,
                BackupSettings.BANK_CARDS, BackupSettings.SMART_REPLIES,
            ),
            R.string.n09_label_sending to listOf(
                BackupSettings.REMOVE_LOCATION, BackupSettings.VOICE_BUTTON, BackupSettings.SIGNATURE,
                BackupSettings.CHARACTER_COUNTER, BackupSettings.SIMPLE_CHARACTERS, BackupSettings.DELIVERY_REPORTS,
                BackupSettings.LONG_AS_MMS, BackupSettings.LONG_AS_MMS_AFTER, BackupSettings.GROUP_AS_MMS,
                BackupSettings.MMS_LIMIT, BackupSettings.DEFAULT_SIM,
            ),
            R.string.n09_label_cleanup to listOf(
                BackupSettings.CODE_CLEANUP_DAYS, BackupSettings.OLD_MESSAGE_DAYS, BackupSettings.WEEKLY_DIGEST,
                BackupSettings.RECYCLE_BIN, BackupSettings.KEEP_ARCHIVED,
            ),
            R.string.n09_label_privacy to listOf(BackupSettings.RISKY_LINKS_LOCKED, BackupSettings.LOCK_SCOPE),
        )
        return groups.flatMap { (label, keys) -> keys.map { it to label } }.toMap()
    }

    @StringRes
    fun labelOf(key: String): Int? = labels[key]

    /** One paragraph per part: rules, forward rules (always switched off) and the settings that change. */
    fun lines(context: Context, preview: SettingsBackup.Preview): String {
        val parts = mutableListOf<String>()
        if (preview.newRules.isNotEmpty()) {
            parts += context.getString(R.string.settings_f_restore_rules, preview.allowRules, preview.blockRules)
        }
        if (preview.rulesPresent > 0) {
            parts += context.getString(R.string.settings_f_restore_rules_present, preview.rulesPresent)
        }
        val tooMany = preview.content.droppedRules + preview.content.droppedForwardRules
        if (tooMany > 0) parts += context.getString(R.string.settings_f_restore_too_many, tooMany)
        val forwards = preview.forwards
        if (forwards.toAdd.isNotEmpty()) {
            parts += context.getString(R.string.settings_f_restore_forwards, forwards.toAdd.size)
        }
        val skipped = forwards.duplicates + forwards.unusable
        if (skipped > 0) parts += context.getString(R.string.settings_f_restore_forwards_skipped, skipped)
        val names = preview.changedSettings.mapNotNull(::labelOf).distinct().map(context::getString)
        parts += if (names.isEmpty()) {
            context.getString(R.string.settings_f_restore_settings_none)
        } else {
            val list = names.joinToString(context.getString(R.string.settings_f_list_separator))
            context.getString(R.string.settings_f_restore_settings_changed, list)
        }
        parts += context.getString(R.string.n09_restore_not_included)
        return parts.joinToString("\n\n")
    }
}

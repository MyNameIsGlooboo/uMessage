@file:Suppress("MaxLineLength")

package com.glooboo.umessage.ui.settings.search

import com.glooboo.umessage.R
import com.glooboo.umessage.ui.settings.search.SettingsIndexEntry.Needs
import com.glooboo.umessage.ui.settings.search.SettingsTarget.ABOUT
import com.glooboo.umessage.ui.settings.search.SettingsTarget.APPEARANCE
import com.glooboo.umessage.ui.settings.search.SettingsTarget.APP_LOCK
import com.glooboo.umessage.ui.settings.search.SettingsTarget.BACKUP
import com.glooboo.umessage.ui.settings.search.SettingsTarget.BLOCKED_NUMBERS
import com.glooboo.umessage.ui.settings.search.SettingsTarget.DECISION_LOG
import com.glooboo.umessage.ui.settings.search.SettingsTarget.DEFAULT_SIM
import com.glooboo.umessage.ui.settings.search.SettingsTarget.FORWARD_OPTIONS
import com.glooboo.umessage.ui.settings.search.SettingsTarget.FORWARD_RULES
import com.glooboo.umessage.ui.settings.search.SettingsTarget.LANGUAGE
import com.glooboo.umessage.ui.settings.search.SettingsTarget.LICENSES
import com.glooboo.umessage.ui.settings.search.SettingsTarget.NOTIFICATIONS
import com.glooboo.umessage.ui.settings.search.SettingsTarget.SPAM
import com.glooboo.umessage.ui.settings.search.SettingsTarget.SPAM_RULES
import com.glooboo.umessage.ui.settings.search.SettingsTarget.SPAM_SETTINGS
import com.glooboo.umessage.ui.settings.search.SettingsTarget.STORAGE
import com.glooboo.umessage.ui.settings.search.SettingsTarget.TYPING
import org.fossify.commons.R as CommonsR

/**
 * Every setting the search can find. To make a new setting searchable, add ONE line to [entries]: the row's title (and
 * summary) string, the page it lives on, where a tap goes, and a `sks_` string with the words people may type for it
 * (add it to values/strings_settings_search.xml and the Persian twin). A unit test checks that all strings exist.
 */
object SettingsIndex {
    private val MAIN = R.string.settings_title
    private val PG_SPAM = R.string.settings_nav_spam_page_title
    private val PG_FWD = R.string.r3fwd_title
    private val PG_NOTIFY = R.string.settings_group_notifications
    private val PG_TYPING = R.string.ux8e_nav_typing
    private val PG_STORAGE = R.string.ux8e_nav_storage
    private val PG_LOOK = R.string.settings_f_appearance
    private val PG_BACKUP = R.string.settings_group_backup
    private val PG_LOCK = R.string.settings_nav_app_lock
    private val PG_ABOUT = R.string.settings_nav_about_row

    val entries: List<SettingsIndexEntry> = spamEntries() + forwardingEntries() + notificationEntries() +
        typingEntries() + storageEntries() + appearanceEntries() + backupEntries() + lockEntries() + aboutEntries()

    private fun spamEntries() = listOf(
        SettingsIndexEntry(R.string.settings_nav_spam_row, MAIN, SPAM, null, R.string.sks_spam),
        SettingsIndexEntry(R.string.spam_settings_unknown_title, PG_SPAM, SPAM, R.string.spam_settings_unknown_body, R.string.sks_unknown_sender),
        SettingsIndexEntry(R.string.spam_mode_strict, PG_SPAM, SPAM, R.string.spam_mode_strict_body, R.string.sks_strict),
        SettingsIndexEntry(R.string.spam_mode_smart, PG_SPAM, SPAM, R.string.spam_mode_smart_body, R.string.sks_smart),
        SettingsIndexEntry(R.string.spam_mode_off, PG_SPAM, SPAM, R.string.spam_mode_off_body, R.string.sks_spam_off),
        SettingsIndexEntry(R.string.settings_spam_open_rules, PG_SPAM, SPAM_RULES, R.string.settings_spam_open_rules_body, R.string.sks_rules),
        SettingsIndexEntry(R.string.rules2_bn_title, PG_SPAM, BLOCKED_NUMBERS, R.string.settings_nav_android_block_list_body, R.string.sks_blocked_numbers),
        SettingsIndexEntry(R.string.settings_nav_decision_log, PG_SPAM, DECISION_LOG, R.string.settings_nav_decision_log_body, R.string.sks_decision_log, Needs.DECISION_LOG),
        SettingsIndexEntry(R.string.settings_nav_purge_row, PG_SPAM, SPAM_SETTINGS, R.string.spam_purge_body, R.string.sks_purge),
        SettingsIndexEntry(R.string.region_title, PG_SPAM, SPAM_SETTINGS, R.string.region_summary, R.string.sks_region),
        SettingsIndexEntry(R.string.i07_lock_title, PG_SPAM, SPAM_SETTINGS, R.string.i07_lock_summary, R.string.sks_links),
        SettingsIndexEntry(R.string.carrier_report_row_title, PG_SPAM, SPAM_SETTINGS, R.string.carrier_report_row_summary, R.string.sks_carrier_report),
        SettingsIndexEntry(R.string.unotify_title, PG_SPAM, SPAM_SETTINGS, R.string.unotify_summary, R.string.sks_unknown_notify),
        SettingsIndexEntry(R.string.notif_open_link_title, PG_SPAM, SPAM_SETTINGS, R.string.notif_open_link_summary, R.string.sks_open_link),
    )

    private fun forwardingEntries() = listOf(
        SettingsIndexEntry(R.string.r3fwd_title, MAIN, FORWARD_RULES, null, R.string.sks_forwarding),
        SettingsIndexEntry(R.string.settings_nav_forwarded_tab, PG_FWD, FORWARD_OPTIONS, R.string.settings_nav_forwarded_tab_body, R.string.sks_forwarded_tab),
        SettingsIndexEntry(R.string.settings_nav_forward_pause, PG_FWD, FORWARD_OPTIONS, R.string.settings_nav_forward_pause_body, R.string.sks_forward_pause),
        SettingsIndexEntry(R.string.w3d_mask_codes, PG_FWD, FORWARD_OPTIONS, R.string.w3d_mask_codes_body, R.string.sks_mask_codes),
        SettingsIndexEntry(R.string.w3d_daily_limit, PG_FWD, FORWARD_OPTIONS, R.string.w3d_daily_limit_body, R.string.sks_daily_limit),
    )

    private fun notificationEntries() = listOf(
        SettingsIndexEntry(R.string.settings_group_notifications, MAIN, NOTIFICATIONS, R.string.ux8e_nav_notifications_body, R.string.sks_notifications),
        SettingsIndexEntry(CommonsR.string.customize_notifications, PG_NOTIFY, NOTIFICATIONS, R.string.settings_nav_open_android_settings, R.string.sks_android_notifications),
        SettingsIndexEntry(R.string.settings_f_code_alert, PG_NOTIFY, NOTIFICATIONS, R.string.settings_f_code_alert_body, R.string.sks_code_alert),
        SettingsIndexEntry(R.string.spam_notify_title, PG_NOTIFY, NOTIFICATIONS, R.string.settings_nav_notify_body, R.string.sks_block_alert),
        SettingsIndexEntry(R.string.digest_row_title, PG_NOTIFY, NOTIFICATIONS, R.string.digest_row_summary, R.string.sks_digest),
        SettingsIndexEntry(R.string.wake_screen_title, PG_NOTIFY, NOTIFICATIONS, R.string.wake_screen_summary, R.string.sks_wake),
        SettingsIndexEntry(R.string.bubbles_title, PG_NOTIFY, NOTIFICATIONS, R.string.bubbles_summary, R.string.sks_bubbles),
        SettingsIndexEntry(R.string.settings_nav_lock_screen, PG_NOTIFY, NOTIFICATIONS, null, R.string.sks_lock_screen),
        SettingsIndexEntry(R.string.w3d_hide_lock_codes, PG_NOTIFY, NOTIFICATIONS, R.string.settings_f_hide_lock_codes_body, R.string.sks_hide_lock_codes),
    )

    private fun typingEntries() = listOf(
        SettingsIndexEntry(R.string.ux8e_nav_typing, MAIN, TYPING, R.string.ux8e_nav_typing_body, R.string.sks_typing),
        SettingsIndexEntry(R.string.ui_settings_character_counter, PG_TYPING, TYPING, null, R.string.sks_counter),
        SettingsIndexEntry(R.string.settings_nav_simple_chars, PG_TYPING, TYPING, R.string.settings_nav_simple_chars_body, R.string.sks_simple_chars),
        SettingsIndexEntry(R.string.ui_settings_send_on_enter, PG_TYPING, TYPING, null, R.string.sks_enter),
        SettingsIndexEntry(R.string.i40_setting_title, PG_TYPING, TYPING, R.string.i40_setting_summary, R.string.sks_voice),
        SettingsIndexEntry(R.string.smart_replies_title, PG_TYPING, TYPING, R.string.smart_replies_summary, R.string.sks_smart_replies),
        SettingsIndexEntry(R.string.smart_replies_notif_title, PG_TYPING, TYPING, R.string.smart_replies_notif_summary, R.string.sks_smart_replies),
        SettingsIndexEntry(R.string.w7d_bank_cards_title, PG_TYPING, TYPING, R.string.w7d_bank_cards_summary, R.string.sks_bank_cards),
        SettingsIndexEntry(R.string.ui_settings_delivery_reports, PG_TYPING, TYPING, null, R.string.sks_delivery),
        SettingsIndexEntry(R.string.long_mms_title, PG_TYPING, TYPING, null, R.string.sks_long_mms),
        SettingsIndexEntry(R.string.settings_nav_group_mms, PG_TYPING, TYPING, R.string.settings_group_mms_body, R.string.sks_group_mms),
        SettingsIndexEntry(R.string.settings_nav_mms_size, PG_TYPING, TYPING, null, R.string.sks_mms_size),
        SettingsIndexEntry(R.string.i09_setting_title, PG_TYPING, TYPING, R.string.i09_setting_summary, R.string.sks_photo_location),
        SettingsIndexEntry(R.string.i60_setting_title, PG_TYPING, TYPING, null, R.string.sks_signature),
        SettingsIndexEntry(R.string.i65_setting_title, PG_TYPING, TYPING, R.string.i65_setting_summary, R.string.sks_chat_sounds),
        SettingsIndexEntry(R.string.i34_setting_title, PG_TYPING, TYPING, R.string.i34_setting_summary, R.string.sks_sim_names, Needs.DUAL_SIM),
        SettingsIndexEntry(R.string.settings_sim_default_title, MAIN, DEFAULT_SIM, null, R.string.sks_sim, Needs.DUAL_SIM),
    )

    private fun storageEntries() = listOf(
        SettingsIndexEntry(R.string.ux8e_nav_storage, MAIN, STORAGE, R.string.ux8e_nav_storage_body, R.string.sks_storage),
        SettingsIndexEntry(R.string.ux8e_keep_deleted, PG_STORAGE, STORAGE, R.string.ux8e_keep_deleted_body, R.string.sks_recycle),
        SettingsIndexEntry(R.string.ux8e_recycle_bin, PG_STORAGE, STORAGE, null, R.string.sks_recycle),
        SettingsIndexEntry(R.string.cleanup_codes_title, PG_STORAGE, STORAGE, R.string.cleanup_codes_summary, R.string.sks_cleanup_codes),
        SettingsIndexEntry(R.string.cleanup_old_title, PG_STORAGE, STORAGE, R.string.cleanup_old_summary, R.string.sks_cleanup_old),
        SettingsIndexEntry(R.string.settings_nav_archived, PG_STORAGE, STORAGE, null, R.string.sks_archived),
        SettingsIndexEntry(R.string.settings_nav_keep_archived, PG_STORAGE, STORAGE, R.string.settings_nav_keep_archived_body, R.string.sks_archived),
    )

    private fun appearanceEntries() = listOf(
        SettingsIndexEntry(R.string.settings_f_appearance, MAIN, APPEARANCE, R.string.settings_nav_theme_body, R.string.sks_theme),
        SettingsIndexEntry(R.string.settings_nav_theme_modes, PG_LOOK, APPEARANCE, null, R.string.sks_dark),
        SettingsIndexEntry(R.string.settings_nav_theme_colours, PG_LOOK, APPEARANCE, null, R.string.sks_colours),
        SettingsIndexEntry(R.string.w3c_text_size, PG_LOOK, APPEARANCE, null, R.string.sks_text_size),
        SettingsIndexEntry(R.string.font_title, PG_LOOK, APPEARANCE, null, R.string.sks_font),
        SettingsIndexEntry(R.string.ux8e_calendar, PG_LOOK, APPEARANCE, null, R.string.sks_calendar),
        SettingsIndexEntry(R.string.i36_unread_first, PG_LOOK, APPEARANCE, R.string.i36_unread_first_body, R.string.sks_unread_first),
        SettingsIndexEntry(CommonsR.string.language, MAIN, LANGUAGE, null, R.string.sks_language, Needs.LANGUAGE_CHOICE),
    )

    private fun backupEntries() = listOf(
        SettingsIndexEntry(R.string.settings_group_backup, MAIN, BACKUP, R.string.settings_f_backup_row_body, R.string.sks_backup),
        SettingsIndexEntry(R.string.ux8e_backup_row, PG_BACKUP, BACKUP, R.string.ux8e_backup_row_body, R.string.sks_backup_do),
        SettingsIndexEntry(R.string.ux8e_restore_row, PG_BACKUP, BACKUP, R.string.ux8e_restore_row_body, R.string.sks_restore),
        SettingsIndexEntry(R.string.sim_messages_title, PG_BACKUP, BACKUP, R.string.sim_messages_row_body, R.string.sks_sim_messages),
    )

    private fun lockEntries() = listOf(
        SettingsIndexEntry(R.string.settings_nav_app_lock, MAIN, APP_LOCK, R.string.settings_nav_app_lock_body, R.string.sks_lock),
        SettingsIndexEntry(R.string.settings_app_lock_timeout, PG_LOCK, APP_LOCK, null, R.string.sks_lock_timeout),
        SettingsIndexEntry(R.string.lock_scope_title, PG_LOCK, APP_LOCK, null, R.string.sks_lock_scope),
        SettingsIndexEntry(R.string.settings_app_lock_screenshots, PG_LOCK, APP_LOCK, R.string.settings_app_lock_screenshots_body, R.string.sks_screenshots),
        SettingsIndexEntry(R.string.settings_app_lock_change, PG_LOCK, APP_LOCK, null, R.string.sks_lock_change),
        SettingsIndexEntry(R.string.settings_app_lock_now, PG_LOCK, APP_LOCK, R.string.settings_app_lock_now_body, R.string.sks_lock_now),
    )

    private fun aboutEntries() = listOf(
        SettingsIndexEntry(R.string.settings_nav_about_row, MAIN, ABOUT, R.string.settings_nav_about_body, R.string.sks_about),
        SettingsIndexEntry(R.string.settings_privacy_title, PG_ABOUT, ABOUT, R.string.settings_nav_privacy_body, R.string.sks_privacy),
        SettingsIndexEntry(R.string.privacy_policy, PG_ABOUT, ABOUT, R.string.privacy_policy_body, R.string.sks_privacy_policy),
        SettingsIndexEntry(R.string.settings_about_version, PG_ABOUT, ABOUT, null, R.string.sks_version),
        SettingsIndexEntry(R.string.settings_about_source, PG_ABOUT, ABOUT, R.string.settings_nav_source_body, R.string.sks_source),
        SettingsIndexEntry(R.string.settings_nav_licenses_row, PG_ABOUT, LICENSES, R.string.settings_nav_licenses_body, R.string.sks_licenses),
    )
}

package com.glooboo.umessage

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.backup.SettingsBackupFormat
import com.glooboo.umessage.databases.MessagesDatabase
import com.glooboo.umessage.helpers.BackupCrypto
import com.glooboo.umessage.helpers.FAILED_SEND_CHANNEL_ID
import com.glooboo.umessage.helpers.MARK_AS_READ
import com.glooboo.umessage.helpers.MutedChannel
import com.glooboo.umessage.helpers.NOTIFICATION_CHANNEL_ID
import com.glooboo.umessage.helpers.REPLY
import com.glooboo.umessage.models.BackupType
import com.glooboo.umessage.receivers.BlockSenderReceiver
import com.glooboo.umessage.receivers.CopyCodeReceiver
import com.glooboo.umessage.receivers.SendStatusReceiver
import com.glooboo.umessage.receivers.SmsStatusSentReceiver
import com.glooboo.umessage.spam.data.DigestNotifier
import com.glooboo.umessage.spam.data.SpamDatabase
import com.glooboo.umessage.spam.mms.BlockedMmsDownloader
import com.glooboo.umessage.ui.spam.RuleNotes
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/**
 * H10: the names that live on in users' phones. A renamed database file, preferences file, notification channel id,
 * action string or backup marker would silently lose data, settings or channel choices after an update, or stop an
 * old backup from being read. Every expected value below is written out on purpose: if one of these tests fails,
 * restore the old name, do not change the test (see the "spamless_" rule in CLAUDE.md).
 *
 * Names a class keeps private are read by reflection from the real constant, so renaming the constant or the class
 * also fails here (with the owner and field in the message).
 */
@RunWith(AndroidJUnit4::class)
class PersistedNamesTest {

    private fun constant(owner: String, field: String): String {
        val cls = Class.forName("com.glooboo.umessage.$owner")
        return cls.getDeclaredField(field).apply { isAccessible = true }.get(null) as String
    }

    private fun assertNames(vararg pins: Triple<String, String, String>) {
        for ((owner, field, expected) in pins) {
            assertWithMessage("$owner.$field").that(constant(owner, field)).isEqualTo(expected)
        }
    }

    // ---- databases ------------------------------------------------------------------------------------------------

    @Test
    fun databaseFileNames() {
        assertThat(SpamDatabase.DATABASE_NAME).isEqualTo("spamless.db")
        assertThat(MessagesDatabase.DATABASE_NAME).isEqualTo("conversations.db")
        assertNames(Triple("databases.RecoveryAttempts", "PREFS", "database_recovery_attempts"))
    }

    // ---- preferences files ----------------------------------------------------------------------------------------

    @Test
    fun preferencesFileNames_ofTheSpamEngine() {
        assertNames(
            Triple("spam.data.SpamSettingsStore", "PREFS_NAME", "spamless_spam_settings"),
            Triple("ui.spam.AndroidForwardLockGate", "PREFS", "spamless_forward"),
            Triple("ui.spam.AndroidRulesOrderStore", "PREFS", "spamless_rules_ui"),
            Triple("ui.spam.BlockedViewModel", "FILTER_PREFS", "spamless_blocked_ui"),
            Triple("ui.spam.BlockedTipStore", "PREFS", "spamless_blocked_ui"),
            Triple("ui.spam.RestoredLog", "PREFS", "spamless_restored"),
            Triple("ui.spam.CarrierReport", "PREFS_NAME", "umessage_carrier_report"),
        )
    }

    @Test
    fun preferencesFileNames_ofTheMessagingScreens() {
        assertNames(
            Triple("helpers.BankCardPrefs", "PREFS_NAME", "spamless_bank_cards"),
            Triple("helpers.ComposerPrefs", "PREFS_NAME", "spamless_composer"),
            Triple("helpers.HiddenChats", "PREFS_NAME", "spamless_hidden_chats"),
            Triple("helpers.MutePrefs", "PREFS_NAME", "spamless_mute"),
            Triple("helpers.NotifyPrefs", "PREFS_NAME", "spamless_notify_extras"),
            Triple("helpers.NavPrefs", "PREFS_NAME", "spamless_nav"),
            Triple("helpers.ThreadPrefs", "PREFS_NAME", "spamless_thread"),
            Triple("helpers.W3aPrefs", "PREFS_NAME", "spamless_w3a"),
            Triple("helpers.W3cPrefs", "PREFS_NAME", "spamless_w3c"),
            Triple("helpers.SearchHistoryPrefs", "PREFS_NAME", "spamless_search_history"),
            Triple("helpers.smartreply.SmartReplyPrefs", "PREFS_NAME", "spamless_smart_replies"),
            Triple("ui.main.BatteryBannerKt", "BANNER_PREFS", "spamless_battery_banner"),
        )
    }

    @Test
    fun preferencesFileNames_ofAppStateAndLocks() {
        assertNames(
            Triple("ui.lock.LockScope", "PREFS_NAME", "spamless_lock_scope"),
            Triple("ui.onboarding.OnboardingState", "PREFS", "spamless_onboarding"),
            Triple("ui.theme.ThemePresets", "PREFS", "spamless_theme"),
            Triple("ui.settings.AppLocale", "PREFS", "umessage_app_locale"),
            Triple("helpers.InstallDefaults", "MARKER_PREFS", "umessage_install"),
            Triple("helpers.CleanupPrefs", "PREFS_NAME", "umessage_cleanup"),
            Triple("helpers.CacheCleaner", "PREFS", "umessage_cache_cleaner"),
            Triple("helpers.ReviewPromptPrefs", "PREFS_NAME", "umessage_review_prompt"),
            Triple("helpers.BackupJob", "PREFS", "backup_job"),
        )
    }

    // ---- notification channels ------------------------------------------------------------------------------------

    @Test
    fun notificationChannelIds() {
        assertThat(NOTIFICATION_CHANNEL_ID).isEqualTo("fossify_messages")
        assertThat(FAILED_SEND_CHANNEL_ID).isEqualTo("umessage_failed_send")
        assertThat(DigestNotifier.CHANNEL_ID).isEqualTo("umessage_weekly_digest")
        assertThat(BlockSenderReceiver.CHANNEL_ID).isEqualTo("sender_blocked_undo")
        assertThat(MutedChannel.ID).isEqualTo("muted_conversations")
        assertNames(
            Triple("spam.data.BlockedNotifier", "CHANNEL_ID", "spamless_blocked"),
            Triple("services.BackupService", "CHANNEL_ID", "backup_restore"),
            Triple("helpers.ReminderNotificationKt", "REMINDER_CHANNEL_ID", "umessage_reminders"),
            Triple("helpers.MmsDownloadFailedNotificationKt", "MMS_DOWNLOAD_CHANNEL_ID", "umessage_mms_download"),
        )
    }

    @Test
    fun notificationTagsAndGroups() {
        assertNames(
            Triple("spam.data.BlockedNotifier", "TAG", "spamless_blocked"),
            Triple("spam.data.BlockedNotifier", "GROUP", "spamless_blocked_group"),
            Triple("spam.data.DigestNotifier", "TAG", "umessage_weekly_digest"),
        )
    }

    // ---- action and extra strings (saved in pending intents and notifications that outlive an update) ---------------

    @Test
    fun actionAndExtraStrings() {
        assertThat(MARK_AS_READ).isEqualTo("com.glooboo.spamless.action.mark_as_read")
        assertThat(REPLY).isEqualTo("com.glooboo.spamless.action.reply")
        assertThat(CopyCodeReceiver.ACTION_COPY_CODE).isEqualTo("com.glooboo.spamless.action.COPY_CODE_BROADCAST")
        assertThat(SendStatusReceiver.SMS_SENT_ACTION).isEqualTo("com.glooboo.spamless.receiver.SMS_SENT")
        assertThat(SendStatusReceiver.SMS_DELIVERED_ACTION).isEqualTo("com.glooboo.spamless.receiver.SMS_DELIVERED")
        assertThat(SendStatusReceiver.EXTRA_PART_COUNT).isEqualTo("com.glooboo.spamless.extra.PART_COUNT")
        assertThat(SmsStatusSentReceiver.EXTRA_FORWARD_LOG_ID).isEqualTo("com.glooboo.spamless.extra.FORWARD_LOG_ID")
        assertThat(SmsStatusSentReceiver.EXTRA_GROUP_ROW_URI).isEqualTo("com.glooboo.spamless.extra.GROUP_ROW_URI")
        assertThat(BlockSenderReceiver.ACTION_BLOCK).isEqualTo("com.glooboo.spamless.action.BLOCK_SENDER")
        assertThat(BlockSenderReceiver.ACTION_UNDO).isEqualTo("com.glooboo.spamless.action.UNDO_BLOCK_SENDER")
        assertThat(BlockSenderReceiver.EXTRA_ADDRESS).isEqualTo("com.glooboo.spamless.extra.BLOCK_ADDRESS")
        assertThat(BlockSenderReceiver.EXTRA_RULE_IDS).isEqualTo("com.glooboo.spamless.extra.BLOCK_RULE_IDS")
        assertThat(BlockedMmsDownloader.EXTRA_BLOCKED_ID).isEqualTo("com.glooboo.spamless.extra.BLOCKED_ID")
        assertThat(BlockedMmsDownloader.EXTRA_FILE).isEqualTo("com.glooboo.spamless.extra.DOWNLOAD_FILE")
        assertNames(
            Triple("services.BackupService", "ACTION_IMPORT", "com.glooboo.spamless.action.IMPORT_MESSAGES"),
            Triple("services.BackupService", "ACTION_EXPORT", "com.glooboo.spamless.action.EXPORT_MESSAGES"),
            Triple("services.BackupService", "ACTION_CANCEL", "com.glooboo.spamless.action.CANCEL_BACKUP"),
            Triple("activities.ThreadActivity", "EXTRA_OPEN_SEARCH", "com.glooboo.spamless.extra.OPEN_THREAD_SEARCH"),
            Triple("activities.SettingsActivity", "EXTRA_PAGE", "com.glooboo.spamless.extra.SETTINGS_PAGE"),
        )
    }

    // ---- backup markers and the note prefix on rules --------------------------------------------------------------

    @Test
    fun protectedBackupMarker_isUmsgenc2_andOlderFilesAreStillRecognized() {
        val out = ByteArrayOutputStream()
        BackupCrypto.encrypt(out, "x".toCharArray(), iterations = 1).use { it.write(1) }
        assertThat(out.toByteArray().copyOf(8).decodeToString()).isEqualTo("UMSGENC2")

        val old = out.toByteArray().also { it[7] = '1'.code.toByte() }
        assertThat(BackupCrypto.isProtected(old.inputStream().buffered())).isTrue()
    }

    @Test
    fun messageBackupTypeNames() {
        val names = BackupType.serializer().descriptor.let { d ->
            (0 until d.elementsCount).map { d.getElementName(it) }
        }
        assertThat(names)
            .containsExactly("sms", "mms", "umessage_notes", "umessage_appdata", "umessage_blocked").inOrder()
    }

    @Test
    fun settingsBackupMarker() {
        assertThat(SettingsBackupFormat.APP).isEqualTo("spamless")
        assertThat(SettingsBackupFormat.KIND).isEqualTo("settings-backup")
        assertThat(SettingsBackupFormat.VERSION).isEqualTo(1)
    }

    @Test
    fun ruleNotePrefixAndCodes() {
        assertNames(Triple("ui.spam.RuleNotes", "PREFIX", "@spamless:"))
        assertThat(RuleNotes.BLOCKED_BY_YOU).isEqualTo("@spamless:blocked_by_you")
        assertThat(RuleNotes.ALLOWED_BY_YOU).isEqualTo("@spamless:allowed_by_you")
        assertThat(RuleNotes.BLOCKED_FROM_MESSAGE).isEqualTo("@spamless:blocked_from_message")
        assertThat(RuleNotes.BLOCKED_FROM_KEYWORD).isEqualTo("@spamless:blocked_from_keyword")
        assertThat(RuleNotes.ALLOWED_FROM_KEYWORD).isEqualTo("@spamless:allowed_from_keyword")
        assertThat(RuleNotes.REPORTED_SPAM).isEqualTo("@spamless:reported_spam")
        assertThat(RuleNotes.OLD_WORD_LIST).isEqualTo("@spamless:old_word_list")
        assertThat(RuleNotes.SYSTEM_LIST).isEqualTo("@spamless:system_list")
    }
}

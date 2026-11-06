package com.glooboo.umessage.helpers

import android.content.Context
import org.fossify.commons.helpers.BaseConfig
import com.glooboo.umessage.extensions.getDefaultKeyboardHeight
import com.glooboo.umessage.models.Conversation

class Config(context: Context) : BaseConfig(context) {
    companion object {
        private const val SYSTEM_THEME_PREF_KEY = "is_using_system_theme"
        fun newInstance(context: Context) = Config(context)
    }

    init {
        // First run only: commons defaults to Material You (system) colours on Android 12+, which
        // would hide the uMessage palette. Start on the brand palette (light/dark follow the system
        // via res/values[-night]/colors.xml); the user can still switch back in Settings.
        val firstRun = !prefs.contains(SYSTEM_THEME_PREF_KEY)
        if (firstRun) {
            prefs.edit().putBoolean(SYSTEM_THEME_PREF_KEY, false).apply()
        }
        InstallDefaults.apply(context, prefs, firstRun)
    }

    fun saveUseSIMIdAtNumber(number: String, SIMId: Int) {
        prefs.edit().putInt(USE_SIM_ID_PREFIX + number, SIMId).apply()
    }

    fun getUseSIMIdAtNumber(number: String) = prefs.getInt(USE_SIM_ID_PREFIX + number, 0)

    /** The subscription id saved for [number], or null if the user never picked one. */
    fun getUseSIMIdAtNumberOrNull(number: String): Int? {
        val key = USE_SIM_ID_PREFIX + number
        return if (prefs.contains(key)) prefs.getInt(key, 0) else null
    }

    /**
     * SIM for conversations with no history: [DEFAULT_SIM_PHONE] follows the phone's setting, [DEFAULT_SIM_ASK] asks on
     * every send, anything else is a subscription id.
     */
    var defaultSimId: Int
        get() = prefs.getInt(DEFAULT_SIM_ID, DEFAULT_SIM_PHONE)
        set(value) = prefs.edit().putInt(DEFAULT_SIM_ID, value).apply()

    var showCharacterCounter: Boolean
        get() = prefs.getBoolean(SHOW_CHARACTER_COUNTER, false)
        set(showCharacterCounter) = prefs.edit()
            .putBoolean(SHOW_CHARACTER_COUNTER, showCharacterCounter).apply()

    var useSimpleCharacters: Boolean
        get() = prefs.getBoolean(USE_SIMPLE_CHARACTERS, false)
        set(useSimpleCharacters) = prefs.edit()
            .putBoolean(USE_SIMPLE_CHARACTERS, useSimpleCharacters).apply()

    var sendOnEnter: Boolean
        get() = prefs.getBoolean(SEND_ON_ENTER, false)
        set(sendOnEnter) = prefs.edit().putBoolean(SEND_ON_ENTER, sendOnEnter).apply()

    var enableDeliveryReports: Boolean
        get() = prefs.getBoolean(ENABLE_DELIVERY_REPORTS, true)
        set(enableDeliveryReports) = prefs.edit()
            .putBoolean(ENABLE_DELIVERY_REPORTS, enableDeliveryReports).apply()

    var sendLongMessageMMS: Boolean
        get() = prefs.getBoolean(SEND_LONG_MESSAGE_MMS, false)
        set(sendLongMessageMMS) = prefs.edit().putBoolean(SEND_LONG_MESSAGE_MMS, sendLongMessageMMS)
            .apply()

    var sendGroupMessageMMS: Boolean
        get() = prefs.getBoolean(SEND_GROUP_MESSAGE_MMS, false)
        set(sendGroupMessageMMS) = prefs.edit()
            .putBoolean(SEND_GROUP_MESSAGE_MMS, sendGroupMessageMMS).apply()

    val isGroupMessageMmsPreferenceResolved: Boolean
        get() = prefs.contains(SEND_GROUP_MESSAGE_MMS)

    /**
     * What a secure lock screen shows of a message notification. Until the user picks a value here, it is
     * [LOCK_SCREEN_SENDER]: the sender, never the text.
     *
     * Older versions wrote a value to prefs on first read (sender and message for updated installs), so a stored
     * value alone does not mean the user chose it. Only values written through this setter after
     * [LOCK_SCREEN_VISIBILITY_CHOSEN] existed count as a choice. The one exception is [LOCK_SCREEN_NOTHING]: no
     * version ever stored it by itself, so it was always the user's choice and stays.
     */
    var lockScreenVisibilitySetting: Int
        get() {
            val stored = prefs.getInt(LOCK_SCREEN_VISIBILITY, LOCK_SCREEN_SENDER)
            return when {
                prefs.getBoolean(LOCK_SCREEN_VISIBILITY_CHOSEN, false) -> stored
                stored == LOCK_SCREEN_NOTHING -> LOCK_SCREEN_NOTHING
                else -> LOCK_SCREEN_SENDER
            }
        }
        set(lockScreenVisibilitySetting) = prefs.edit()
            .putInt(LOCK_SCREEN_VISIBILITY, lockScreenVisibilitySetting)
            .putBoolean(LOCK_SCREEN_VISIBILITY_CHOSEN, true)
            .apply()

    var mmsFileSizeLimit: Long
        get() = prefs.getLong(MMS_FILE_SIZE_LIMIT, FILE_SIZE_200_KB)
        set(mmsFileSizeLimit) = prefs.edit().putLong(MMS_FILE_SIZE_LIMIT, mmsFileSizeLimit).apply()

    var pinnedConversations: Set<String>
        get() = prefs.getStringSet(PINNED_CONVERSATIONS, HashSet<String>())!!
        set(pinnedConversations) = prefs.edit()
            .putStringSet(PINNED_CONVERSATIONS, pinnedConversations).apply()

    fun addPinnedConversationByThreadId(threadId: Long) {
        pinnedConversations = pinnedConversations.plus(threadId.toString())
    }

    fun addPinnedConversations(conversations: List<Conversation>) {
        pinnedConversations = pinnedConversations.plus(conversations.map { it.threadId.toString() })
    }

    fun removePinnedConversationByThreadId(threadId: Long) {
        pinnedConversations = pinnedConversations.minus(threadId.toString())
    }

    fun removePinnedConversations(conversations: List<Conversation>) {
        pinnedConversations =
            pinnedConversations.minus(conversations.map { it.threadId.toString() })
    }

    /**
     * Scheduled messages (by id) whose alarm is inexact: set without "Alarms & reminders" access ("Schedule
     * anyway"), so they may go out a few minutes late. Their bubble says the time is approximate (RM-27).
     */
    var inexactScheduledMessages: Set<String>
        get() = prefs.getStringSet(INEXACT_SCHEDULED_MESSAGES, HashSet<String>())!!
        set(ids) = prefs.edit().putStringSet(INEXACT_SCHEDULED_MESSAGES, ids).apply()

    fun setScheduledMessageInexact(messageId: Long, inexact: Boolean) {
        val id = messageId.toString()
        val current = inexactScheduledMessages
        if (inexact != (id in current)) inexactScheduledMessages = if (inexact) current + id else current - id
    }

    /**
     * Threads the user marked unread. Unread is otherwise derived from unread incoming messages, so a thread with
     * only sent messages would show read again on the next refresh without this. Cleared when the thread is read.
     */
    var markedUnreadThreads: Set<String>
        get() = prefs.getStringSet(MARKED_UNREAD_THREADS, HashSet<String>())!!
        set(markedUnreadThreads) = prefs.edit()
            .putStringSet(MARKED_UNREAD_THREADS, markedUnreadThreads).apply()

    fun setThreadMarkedUnread(threadId: Long, markedUnread: Boolean) {
        val id = threadId.toString()
        if (markedUnread != (id in markedUnreadThreads)) {
            markedUnreadThreads = if (markedUnread) markedUnreadThreads.plus(id) else markedUnreadThreads.minus(id)
        }
    }

    var blockedKeywords: Set<String>
        // Blank entries (left by old imports) would match nearly every message, so they are never read back.
        get() = prefs.getStringSet(BLOCKED_KEYWORDS, HashSet<String>())!!.filterTo(HashSet()) { it.isNotBlank() }
        set(blockedKeywords) = prefs.edit().putStringSet(BLOCKED_KEYWORDS, blockedKeywords).apply()

    /** SU-33: the user moved the old block lists into uMessage rules, or said "Not now" to the offer. */
    var legacyBlockOfferDismissed: Boolean
        get() = prefs.getBoolean(LEGACY_BLOCK_OFFER_DISMISSED, false)
        set(value) = prefs.edit().putBoolean(LEGACY_BLOCK_OFFER_DISMISSED, value).apply()

    /** Trimmed; blank, one-letter and same-but-for-case keywords are not added. */
    fun addBlockedKeyword(keyword: String) {
        blockedKeywords = BlockedKeywordsFormat.add(blockedKeywords, keyword)
    }

    fun removeBlockedKeyword(keyword: String) {
        blockedKeywords = blockedKeywords.minus(keyword)
    }

    var exportSms: Boolean
        get() = prefs.getBoolean(EXPORT_SMS, true)
        set(exportSms) = prefs.edit().putBoolean(EXPORT_SMS, exportSms).apply()

    var exportMms: Boolean
        get() = prefs.getBoolean(EXPORT_MMS, true)
        set(exportMms) = prefs.edit().putBoolean(EXPORT_MMS, exportMms).apply()

    var importSms: Boolean
        get() = prefs.getBoolean(IMPORT_SMS, true)
        set(importSms) = prefs.edit().putBoolean(IMPORT_SMS, importSms).apply()

    var importMms: Boolean
        get() = prefs.getBoolean(IMPORT_MMS, true)
        set(importMms) = prefs.edit().putBoolean(IMPORT_MMS, importMms).apply()

    var wasDbCleared: Boolean
        get() = prefs.getBoolean(WAS_DB_CLEARED, false)
        set(wasDbCleared) = prefs.edit().putBoolean(WAS_DB_CLEARED, wasDbCleared).apply()

    var keyboardHeight: Int
        get() = prefs.getInt(SOFT_KEYBOARD_HEIGHT, context.getDefaultKeyboardHeight())
        set(keyboardHeight) = prefs.edit().putInt(SOFT_KEYBOARD_HEIGHT, keyboardHeight).apply()

    var useRecycleBin: Boolean
        get() = prefs.getBoolean(USE_RECYCLE_BIN, false)
        set(useRecycleBin) = prefs.edit().putBoolean(USE_RECYCLE_BIN, useRecycleBin).apply()

    var lastRecycleBinCheck: Long
        get() = prefs.getLong(LAST_RECYCLE_BIN_CHECK, 0L)
        set(lastRecycleBinCheck) = prefs.edit().putLong(LAST_RECYCLE_BIN_CHECK, lastRecycleBinCheck)
            .apply()

    var isArchiveAvailable: Boolean
        get() = prefs.getBoolean(IS_ARCHIVE_AVAILABLE, true)
        set(isArchiveAvailable) = prefs.edit().putBoolean(IS_ARCHIVE_AVAILABLE, isArchiveAvailable)
            .apply()

    /** When the Blocked tab was last shown (epoch millis); messages blocked after it count towards the tab badge. */
    var blockedSeenAt: Long
        get() = prefs.getLong(BLOCKED_SEEN_AT, 0L)
        set(blockedSeenAt) = prefs.edit().putLong(BLOCKED_SEEN_AT, blockedSeenAt).apply()

    var customNotifications: Set<String>
        get() = prefs.getStringSet(CUSTOM_NOTIFICATIONS, HashSet<String>())!!
        set(customNotifications) = prefs.edit()
            .putStringSet(CUSTOM_NOTIFICATIONS, customNotifications).apply()

    fun addCustomNotificationsByThreadId(threadId: Long) {
        customNotifications = customNotifications.plus(threadId.toString())
    }

    fun removeCustomNotificationsByThreadId(threadId: Long) {
        customNotifications = customNotifications.minus(threadId.toString())
    }

    var lastBlockedKeywordExportPath: String
        get() = prefs.getString(LAST_BLOCKED_KEYWORD_EXPORT_PATH, "")!!
        set(lastBlockedNumbersExportPath) = prefs.edit()
            .putString(LAST_BLOCKED_KEYWORD_EXPORT_PATH, lastBlockedNumbersExportPath).apply()

    var keepConversationsArchived: Boolean
        get() = prefs.getBoolean(KEEP_CONVERSATIONS_ARCHIVED, false)
        set(keepConversationsArchived) = prefs.edit()
            .putBoolean(KEEP_CONVERSATIONS_ARCHIVED, keepConversationsArchived).apply()
}

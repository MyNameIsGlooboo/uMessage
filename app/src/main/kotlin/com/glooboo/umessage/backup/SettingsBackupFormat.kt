package com.glooboo.umessage.backup

import com.glooboo.umessage.spam.ForwardRule
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamRule
import com.glooboo.umessage.spam.UnknownInboxNotify
import com.glooboo.umessage.spam.UnknownSenderMode
import com.glooboo.umessage.ui.spam.ForwardTargets
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The file is not a uMessage settings backup, or is too damaged to read. */
class BackupFormatException(message: String) : Exception(message)

/**
 * The settings that go into a backup. A null value is "not in the file" and leaves the phone's setting as it is. No
 * message text, no app-lock data (PIN, lock state) and nothing tied to this phone (SIM ids or names, chat ids, saved
 * texts, hidden chats, mute times, dismissed cards, run times) is part of it. Version 1 files written before the
 * fields after [forwardPaused] existed still read: those settings are simply not in them.
 */
data class BackupSettings(
    val spamMode: UnknownSenderMode? = null,
    val purgeAfterDays: Int? = null,
    val notifyOnBlock: Boolean? = null,
    val showForwardedTab: Boolean? = null,
    val archivedIntroDismissed: Boolean? = null,
    val useJalaliDates: Boolean? = null,
    val maskForwardedCodes: Boolean? = null,
    val maskKeepLastDigits: Boolean? = null,
    val hideCodesOnLockScreen: Boolean? = null,
    val dailyForwardLimit: Int? = null,
    /** [com.glooboo.umessage.helpers.DateStyle.key]; wins over [useJalaliDates] when both are present (L2). */
    val dateStyle: String? = null,
    /** [com.glooboo.umessage.helpers.TextSize.key]. */
    val textSize: String? = null,
    /** "MODE/COLOURS" (ThemeMode/ThemeColours names), or an older single PresetId name. */
    val theme: String? = null,
    val sendOnEnter: Boolean? = null,
    /** Lock-screen privacy of message notifications (the LOCK_SCREEN_* values). */
    val lockScreenVisibility: Int? = null,
    /** "Pause all forwarding". A restore only ever turns it on, never off. */
    val forwardPaused: Boolean? = null,
    val unknownInboxNotify: UnknownInboxNotify? = null,
    val openLinkAction: Boolean? = null,
    /** ISO region of the home country for "block numbers from other countries"; empty = off. */
    val blockOutsideRegion: String? = null,
    val unreadFirst: Boolean? = null,
    val pinchZoomText: Boolean? = null,
    val showTapbacks: Boolean? = null,
    val riskyLinksLocked: Boolean? = null,
    val bankCards: Boolean? = null,
    /** Days after which read codes go to the recycle bin; 0 = never. */
    val codeCleanupDays: Int? = null,
    /** Days after which old messages go to the recycle bin; 0 = never. */
    val oldMessageDays: Int? = null,
    val weeklyDigest: Boolean? = null,
    /** The carrier's short number for "Report spam"; empty = not set. */
    val carrierSpamNumber: String? = null,
    val removePhotoLocation: Boolean? = null,
    val voiceButton: Boolean? = null,
    val signature: String? = null,
    val chatSounds: Boolean? = null,
    val wakeScreen: Boolean? = null,
    val chatBubbles: Boolean? = null,
    val focusCodes: Boolean? = null,
    /** A tap on a notification that holds only a code copies it. Files from before 0.4.5 lack it: the default is on. */
    val copyCodeOnTap: Boolean? = null,
    val smartReplies: Boolean? = null,
    /** [com.glooboo.umessage.ui.lock.LockScope.key]. The lock itself is never part of a backup. */
    val lockScope: String? = null,
    val showCharacterCounter: Boolean? = null,
    val useSimpleCharacters: Boolean? = null,
    val deliveryReports: Boolean? = null,
    /** Older files only: the on/off switch of 0.4.1 and before. On means "from 2 parts"; see [sendLongAsMmsAfter]. */
    val sendLongAsMms: Boolean? = null,
    /** Q9: SMS parts from which a long text goes as MMS; 0 = never. Older files lack it and keep the default. */
    val sendLongAsMmsAfter: Int? = null,
    val sendGroupAsMms: Boolean? = null,
    /** Bytes; one of the sizes the setting offers, or -1 for no limit. */
    val mmsFileSizeLimit: Long? = null,
    val useRecycleBin: Boolean? = null,
    val keepArchived: Boolean? = null,
    /** "phone" (follow the phone) or "ask". A chosen SIM is never written: its id belongs to this phone. */
    val defaultSim: String? = null,
    /** [com.glooboo.umessage.helpers.AppFont.key]. */
    val appFont: String? = null,
) {
    /** Every setting by its JSON key, in file order; null is "not in the file". */
    internal fun values(): Map<String, Any?> = coreValues() + moreValues()

    private fun coreValues(): Map<String, Any?> = linkedMapOf(
        SPAM_MODE to spamMode, PURGE_DAYS to purgeAfterDays,
        NOTIFY_ON_BLOCK to notifyOnBlock, SHOW_FORWARDED_TAB to showForwardedTab,
        ARCHIVED_INTRO to archivedIntroDismissed, JALALI to useJalaliDates, MASK_CODES to maskForwardedCodes,
        MASK_KEEP_LAST to maskKeepLastDigits, HIDE_LOCK_CODES to hideCodesOnLockScreen,
        DAILY_LIMIT to dailyForwardLimit, DATE_STYLE to dateStyle, TEXT_SIZE to textSize, THEME to theme,
        SEND_ON_ENTER to sendOnEnter, LOCK_SCREEN to lockScreenVisibility, FORWARD_PAUSED to forwardPaused,
    )

    private fun moreValues(): Map<String, Any?> = linkedMapOf(
        UNKNOWN_NOTIFY to unknownInboxNotify, OPEN_LINK to openLinkAction, OUTSIDE_REGION to blockOutsideRegion,
        UNREAD_FIRST to unreadFirst, PINCH_ZOOM to pinchZoomText, TAPBACKS to showTapbacks,
        RISKY_LINKS_LOCKED to riskyLinksLocked, BANK_CARDS to bankCards, CODE_CLEANUP_DAYS to codeCleanupDays,
        OLD_MESSAGE_DAYS to oldMessageDays, WEEKLY_DIGEST to weeklyDigest, CARRIER_NUMBER to carrierSpamNumber,
        REMOVE_LOCATION to removePhotoLocation, VOICE_BUTTON to voiceButton, SIGNATURE to signature,
        CHAT_SOUNDS to chatSounds, WAKE_SCREEN to wakeScreen, CHAT_BUBBLES to chatBubbles, FOCUS_CODES to focusCodes,
        COPY_CODE_ON_TAP to copyCodeOnTap,
        SMART_REPLIES to smartReplies, LOCK_SCOPE to lockScope, CHARACTER_COUNTER to showCharacterCounter,
        SIMPLE_CHARACTERS to useSimpleCharacters, DELIVERY_REPORTS to deliveryReports, LONG_AS_MMS to sendLongAsMms,
        LONG_AS_MMS_AFTER to sendLongAsMmsAfter, GROUP_AS_MMS to sendGroupAsMms, MMS_LIMIT to mmsFileSizeLimit,
        RECYCLE_BIN to useRecycleBin,
        KEEP_ARCHIVED to keepArchived, DEFAULT_SIM to defaultSim, APP_FONT to appFont,
    )

    /** The JSON keys of the settings, also the names [SettingsBackupFormat.changedSettings] returns. */
    companion object {
        const val SPAM_MODE = "spamMode"
        /**
         * The old "codes from unknown senders" setting. Files written before 0.4.5 carry it; it is no longer written,
         * shown or applied, and reading a file simply ignores the value.
         */
        const val OTP_EXEMPTION = "otpExemption"
        const val PURGE_DAYS = "purgeAfterDays"
        const val NOTIFY_ON_BLOCK = "notifyOnBlock"
        const val SHOW_FORWARDED_TAB = "showForwardedTab"
        const val ARCHIVED_INTRO = "archivedIntroDismissed"
        const val JALALI = "useJalaliDates"
        const val MASK_CODES = "maskForwardedCodes"
        const val MASK_KEEP_LAST = "maskKeepLastDigits"
        const val HIDE_LOCK_CODES = "hideCodesOnLockScreen"
        const val DAILY_LIMIT = "dailyForwardLimit"
        const val DATE_STYLE = "dateStyle"
        const val TEXT_SIZE = "textSize"
        const val THEME = "theme"
        const val SEND_ON_ENTER = "sendOnEnter"
        const val LOCK_SCREEN = "lockScreenVisibility"
        const val FORWARD_PAUSED = "forwardPaused"
        const val UNKNOWN_NOTIFY = "unknownInboxNotify"
        const val OPEN_LINK = "openLinkAction"
        const val OUTSIDE_REGION = "blockOutsideRegion"
        const val UNREAD_FIRST = "unreadFirst"
        const val PINCH_ZOOM = "pinchZoomText"
        const val TAPBACKS = "showTapbacks"
        const val RISKY_LINKS_LOCKED = "riskyLinksLocked"
        const val BANK_CARDS = "bankCards"
        const val CODE_CLEANUP_DAYS = "codeCleanupDays"
        const val OLD_MESSAGE_DAYS = "oldMessageDays"
        const val WEEKLY_DIGEST = "weeklyDigest"
        const val CARRIER_NUMBER = "carrierSpamNumber"
        const val REMOVE_LOCATION = "removePhotoLocation"
        const val VOICE_BUTTON = "voiceButton"
        const val SIGNATURE = "signature"
        const val CHAT_SOUNDS = "chatSounds"
        const val WAKE_SCREEN = "wakeScreen"
        const val CHAT_BUBBLES = "chatBubbles"
        const val FOCUS_CODES = "focusCodes"
        const val COPY_CODE_ON_TAP = "copyCodeOnTap"
        const val SMART_REPLIES = "smartReplies"
        const val LOCK_SCOPE = "lockScope"
        const val CHARACTER_COUNTER = "showCharacterCounter"
        const val SIMPLE_CHARACTERS = "useSimpleCharacters"
        const val DELIVERY_REPORTS = "deliveryReports"
        const val LONG_AS_MMS = "sendLongAsMms"
        const val LONG_AS_MMS_AFTER = "sendLongAsMmsAfter"
        const val GROUP_AS_MMS = "sendGroupAsMms"
        const val MMS_LIMIT = "mmsFileSizeLimit"
        const val RECYCLE_BIN = "useRecycleBin"
        const val KEEP_ARCHIVED = "keepArchived"
        const val DEFAULT_SIM = "defaultSim"
        const val APP_FONT = "appFont"

        /** The two values of [defaultSim]. */
        const val SIM_PHONE = "phone"
        const val SIM_ASK = "ask"
    }
}

/**
 * Forward rules of a backup checked against the phone (H1): [toAdd] are new, switched off and have clean targets;
 * [duplicates] are already on the phone (or twice in the file); [unusable] have no target left that may be sent to.
 */
data class ForwardImport(
    val toAdd: List<ForwardRule>,
    val duplicates: Int,
    val unusable: Int,
)

/** What a backup file holds: the allow and block rules, the forward rules and the settings. */
data class BackupContent(
    val rules: List<SpamRule>,
    val forwardRules: List<ForwardRule>,
    val settings: BackupSettings,
    /** Version of the app that wrote the file; information only, never applied. Null in files from before N09. */
    val appVersion: String? = null,
)

/**
 * The versioned JSON of a settings backup (ID-38). Pure JVM. Reading is tolerant: unknown fields are ignored, an entry
 * with an unknown type or no usable value is skipped, and numbers and flags of the wrong type are treated as missing,
 * so a backup of a newer version still restores what this version understands.
 *
 * ```
 * { "app": "spamless", "kind": "settings-backup", "version": 1, "exportedAt": 1730000000000, "appVersion": "1.2.3",
 *   "rules": [ { "kind": "BLOCK", "type": "KEYWORD", "pattern": "...", "note": "", "createdAt": 0 } ],
 *   "forwardRules": [ { "senderType": "NUMBER", "senderPattern": "...", "bodyType": null, "bodyPattern": "",
 *                       "windowStart": null, "windowEnd": null, "targets": ["+..."], "sendFrom": "SAME",
 *                       "includeCodes": false, "enabled": true, "note": "", "createdAt": 0 } ],
 *   "settings": { "spamMode": "SMART", ... } }
 * ```
 */
object SettingsBackupFormat {
    const val APP = "spamless"
    const val KIND = "settings-backup"
    const val VERSION = 1

    /** Bounds so a huge or hostile file cannot fill the database. */
    const val MAX_RULES = 5000
    const val MAX_FORWARD_RULES = 200
    const val MAX_PATTERN = 500
    const val MAX_NOTE = 200
    const val MAX_TARGET = 40
    const val MAX_VERSION = 40

    private val json = Json { prettyPrint = true }

    fun encode(content: BackupContent, exportedAt: Long): String {
        val root = buildJsonObject {
            put("app", APP)
            put("kind", KIND)
            put("version", VERSION)
            put("exportedAt", exportedAt)
            content.appVersion?.let { put("appVersion", it) }
            put("rules", buildJsonArray { content.rules.forEach { add(BackupJson.rule(it)) } })
            put("forwardRules", buildJsonArray { content.forwardRules.forEach { add(BackupJson.forward(it)) } })
            put("settings", BackupJson.settings(content.settings))
        }
        return json.encodeToString(JsonElement.serializer(), root)
    }

    /** Throws [BackupFormatException] when [text] is not JSON, or not a uMessage settings backup. */
    fun decode(text: String): BackupContent {
        val parsed: JsonElement? = try {
            Json.parseToJsonElement(text)
        } catch (_: IllegalArgumentException) {
            null
        }
        val root = parsed as? JsonObject
        val isBackup = root != null && root.str("app") == APP && root.str("kind") == KIND &&
            (root.int("version") ?: 0) >= 1
        if (root == null || !isBackup) throw BackupFormatException("Not a uMessage settings backup")
        return BackupContent(
            rules = root.array("rules").orEmpty().asSequence().mapNotNull(BackupJson::readRule)
                .take(MAX_RULES).toList(),
            forwardRules = root.array("forwardRules").orEmpty().asSequence().mapNotNull(BackupJson::readForward)
                .take(MAX_FORWARD_RULES).toList(),
            settings = BackupSettingsJson.readSettings(root["settings"] as? JsonObject),
            appVersion = root.str("appVersion")?.take(MAX_VERSION),
        )
    }

    /**
     * Two forward rules with the same conditions (time window and codes included), sending SIM and targets are the
     * same rule, so importing twice adds nothing (L3). Targets compare by their last digits: 0555… matches +1555….
     */
    fun forwardKey(rule: ForwardRule): String = listOf(
        rule.senderType?.name.orEmpty(), rule.senderPattern.trim().lowercase(),
        rule.bodyType?.name.orEmpty(), rule.bodyPattern.trim(),
        rule.windowStart?.toString().orEmpty(), rule.windowEnd?.toString().orEmpty(),
        rule.includeCodes.toString(), rule.sendFrom.name,
        rule.targets.map { ForwardTargets.key(it).ifEmpty { it.trim() } }.distinct().sorted().joinToString(","),
    ).joinToString("|")

    /**
     * The settings of [incoming] that differ from [current], by JSON key, for the summary shown before a restore. A
     * pause switched off in the file is never applied, so it is not a change.
     */
    fun changedSettings(current: BackupSettings, incoming: BackupSettings): List<String> {
        val now = current.values()
        return incoming.values().filter { (key, value) ->
            value != null && value != now[key] && !(key == BackupSettings.FORWARD_PAUSED && value == false)
        }.keys.toList()
    }

    /**
     * Checks the forward rules of a backup before they are added (H1). Each target goes through the forward editor's
     * checks ([ForwardTargets]): a real phone number (no short codes), not one of [ownNumbers], not the watched sender;
     * it is stored normalized (a national 0 becomes the country code). A rule with no target left is dropped. Every
     * rule comes in switched off and without a SIM scope, so nothing is forwarded until the owner turns it on in the
     * forward editor, which asks for the app lock. Rules already on the phone ([existing]) or twice in the file are
     * skipped.
     */
    fun prepareForwardImport(
        incoming: List<ForwardRule>,
        existing: List<ForwardRule>,
        ownNumbers: List<String> = emptyList(),
    ): ForwardImport {
        val known = existing.map(::forwardKey).toMutableSet()
        val toAdd = mutableListOf<ForwardRule>()
        var duplicates = 0
        var unusable = 0
        for (rule in incoming) {
            val watched = rule.senderPattern.takeIf { rule.senderType == RuleType.NUMBER }
            val errors = ForwardTargets.errors(rule.targets, ownNumbers, watched)
            val targets = rule.targets.zip(errors)
                .filter { (_, error) -> error == null }
                .mapNotNull { (raw, _) -> ForwardTargets.stored(raw) }
                .distinctBy(ForwardTargets::key)
            if (targets.isEmpty()) {
                unusable++
                continue
            }
            val clean = rule.copy(id = 0, subscriptionId = null, targets = targets, enabled = false)
            if (known.add(forwardKey(clean))) toAdd += clean else duplicates++
        }
        return ForwardImport(toAdd, duplicates, unusable)
    }

}

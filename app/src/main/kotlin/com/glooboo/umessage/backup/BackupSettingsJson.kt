package com.glooboo.umessage.backup

import com.glooboo.umessage.helpers.FILE_SIZE_100_KB
import com.glooboo.umessage.helpers.FILE_SIZE_1_MB
import com.glooboo.umessage.helpers.FILE_SIZE_200_KB
import com.glooboo.umessage.helpers.FILE_SIZE_2_MB
import com.glooboo.umessage.helpers.FILE_SIZE_300_KB
import com.glooboo.umessage.helpers.FILE_SIZE_600_KB
import com.glooboo.umessage.helpers.FILE_SIZE_NONE
import com.glooboo.umessage.helpers.LOCK_SCREEN_NOTHING
import com.glooboo.umessage.helpers.LOCK_SCREEN_SENDER
import com.glooboo.umessage.helpers.LOCK_SCREEN_SENDER_MESSAGE
import com.glooboo.umessage.messaging.LongTextMms
import com.glooboo.umessage.spam.UnknownInboxNotify
import com.glooboo.umessage.spam.UnknownSenderMode
import kotlinx.serialization.json.JsonObject

/** Reads the settings entry of a settings backup. Tolerant like [BackupJson]: bad values are missing. */
internal object BackupSettingsJson {
    private const val MAX_PURGE_DAYS = 3650
    private const val MAX_DAILY_LIMIT = 1000
    private const val MAX_SIGNATURE = 1000
    private const val REGION_LENGTH = 2
    private const val MIN_CARRIER_DIGITS = 3
    private const val MAX_CARRIER_DIGITS = 8

    /** The keys of DateStyle and TextSize, so this file stays free of Android classes. */
    private val DATE_STYLES = setOf("gregorian", "jalali", "both")
    private val TEXT_SIZES = setOf("xsmall", "small", "default", "large", "largest", "huge", "giant")
    private val APP_FONTS = setOf("umessage", "system")
    private val LOCK_SCOPES = setOf("whole", "chosen")
    private val MMS_LIMITS = setOf(
        FILE_SIZE_NONE, FILE_SIZE_100_KB, FILE_SIZE_200_KB, FILE_SIZE_300_KB, FILE_SIZE_600_KB, FILE_SIZE_1_MB,
        FILE_SIZE_2_MB,
    )
    private val DEFAULT_SIMS = setOf(BackupSettings.SIM_PHONE, BackupSettings.SIM_ASK)
    private val LOCK_SCREEN_VALUES = setOf(LOCK_SCREEN_SENDER_MESSAGE, LOCK_SCREEN_SENDER, LOCK_SCREEN_NOTHING)

    fun readSettings(o: JsonObject?): BackupSettings {
        if (o == null) return BackupSettings()
        return readBasics(o).let { readSpamAndDisplay(o, it) }.let { readCleanupAndSending(o, it) }
            .let { readNotifyAndMessages(o, it) }
    }

    private fun readBasics(o: JsonObject): BackupSettings =
        BackupSettings(
            spamMode = enumOf<UnknownSenderMode>(o.str(BackupSettings.SPAM_MODE)),
            purgeAfterDays = o.int(BackupSettings.PURGE_DAYS)?.takeIf { it in 0..MAX_PURGE_DAYS },
            notifyOnBlock = o.bool(BackupSettings.NOTIFY_ON_BLOCK),
            showForwardedTab = o.bool(BackupSettings.SHOW_FORWARDED_TAB),
            archivedIntroDismissed = o.bool(BackupSettings.ARCHIVED_INTRO),
            useJalaliDates = o.bool(BackupSettings.JALALI),
            maskForwardedCodes = o.bool(BackupSettings.MASK_CODES),
            maskKeepLastDigits = o.bool(BackupSettings.MASK_KEEP_LAST),
            hideCodesOnLockScreen = o.bool(BackupSettings.HIDE_LOCK_CODES),
            dailyForwardLimit = o.int(BackupSettings.DAILY_LIMIT)?.takeIf { it in 0..MAX_DAILY_LIMIT },
            dateStyle = o.str(BackupSettings.DATE_STYLE)?.takeIf { it in DATE_STYLES },
            textSize = o.str(BackupSettings.TEXT_SIZE)?.takeIf { it in TEXT_SIZES },
            theme = o.str(BackupSettings.THEME)?.takeIf(::isPresetName),
            sendOnEnter = o.bool(BackupSettings.SEND_ON_ENTER),
            lockScreenVisibility = o.int(BackupSettings.LOCK_SCREEN)?.takeIf { it in LOCK_SCREEN_VALUES },
            forwardPaused = o.bool(BackupSettings.FORWARD_PAUSED),
        )

    private fun readSpamAndDisplay(o: JsonObject, s: BackupSettings) = s.copy(
        unknownInboxNotify = enumOf<UnknownInboxNotify>(o.str(BackupSettings.UNKNOWN_NOTIFY)),
        openLinkAction = o.bool(BackupSettings.OPEN_LINK),
        blockOutsideRegion = o.str(BackupSettings.OUTSIDE_REGION)?.takeIf(::isRegionOrEmpty),
        unreadFirst = o.bool(BackupSettings.UNREAD_FIRST),
        pinchZoomText = o.bool(BackupSettings.PINCH_ZOOM),
        showTapbacks = o.bool(BackupSettings.TAPBACKS),
        riskyLinksLocked = o.bool(BackupSettings.RISKY_LINKS_LOCKED),
        bankCards = o.bool(BackupSettings.BANK_CARDS),
    )

    private fun readCleanupAndSending(o: JsonObject, s: BackupSettings) = s.copy(
        codeCleanupDays = o.int(BackupSettings.CODE_CLEANUP_DAYS)?.takeIf { it in 0..MAX_PURGE_DAYS },
        oldMessageDays = o.int(BackupSettings.OLD_MESSAGE_DAYS)?.takeIf { it in 0..MAX_PURGE_DAYS },
        weeklyDigest = o.bool(BackupSettings.WEEKLY_DIGEST),
        carrierSpamNumber = o.str(BackupSettings.CARRIER_NUMBER)?.takeIf(::isCarrierNumberOrEmpty),
        removePhotoLocation = o.bool(BackupSettings.REMOVE_LOCATION),
        voiceButton = o.bool(BackupSettings.VOICE_BUTTON),
        signature = o.str(BackupSettings.SIGNATURE)?.takeIf { it.length <= MAX_SIGNATURE },
        chatSounds = o.bool(BackupSettings.CHAT_SOUNDS),
    )

    private fun readNotifyAndMessages(o: JsonObject, s: BackupSettings) = s.copy(
        wakeScreen = o.bool(BackupSettings.WAKE_SCREEN),
        chatBubbles = o.bool(BackupSettings.CHAT_BUBBLES),
        focusCodes = o.bool(BackupSettings.FOCUS_CODES),
        copyCodeOnTap = o.bool(BackupSettings.COPY_CODE_ON_TAP),
        smartReplies = o.bool(BackupSettings.SMART_REPLIES),
        lockScope = o.str(BackupSettings.LOCK_SCOPE)?.takeIf { it in LOCK_SCOPES },
        showCharacterCounter = o.bool(BackupSettings.CHARACTER_COUNTER),
        useSimpleCharacters = o.bool(BackupSettings.SIMPLE_CHARACTERS),
        deliveryReports = o.bool(BackupSettings.DELIVERY_REPORTS),
        sendLongAsMms = o.bool(BackupSettings.LONG_AS_MMS),
        sendLongAsMmsAfter = o.int(BackupSettings.LONG_AS_MMS_AFTER)?.takeIf { it in LongTextMms.CHOICES },
        sendGroupAsMms = o.bool(BackupSettings.GROUP_AS_MMS),
        mmsFileSizeLimit = o.long(BackupSettings.MMS_LIMIT)?.takeIf { it in MMS_LIMITS },
        useRecycleBin = o.bool(BackupSettings.RECYCLE_BIN),
        keepArchived = o.bool(BackupSettings.KEEP_ARCHIVED),
        defaultSim = o.str(BackupSettings.DEFAULT_SIM)?.takeIf { it in DEFAULT_SIMS },
        appFont = o.str(BackupSettings.APP_FONT)?.takeIf { it in APP_FONTS },
    )

    /** An ISO region such as "GB", or empty for "off". */
    private fun isRegionOrEmpty(value: String) =
        value.isEmpty() || (value.length == REGION_LENGTH && value.all { it in 'A'..'Z' })

    private fun isCarrierNumberOrEmpty(value: String) = value.isEmpty() ||
        (value.length in MIN_CARRIER_DIGITS..MAX_CARRIER_DIGITS && value.all { it in '0'..'9' })
}

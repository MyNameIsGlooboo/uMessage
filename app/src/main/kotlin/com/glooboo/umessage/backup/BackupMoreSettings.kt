package com.glooboo.umessage.backup

import android.content.Context
import com.glooboo.umessage.extensions.config
import com.glooboo.umessage.helpers.AppFont
import com.glooboo.umessage.helpers.BankCardPrefs
import com.glooboo.umessage.helpers.DEFAULT_SIM_ASK
import com.glooboo.umessage.helpers.DEFAULT_SIM_PHONE
import com.glooboo.umessage.helpers.ThreadPrefs
import com.glooboo.umessage.helpers.W3cPrefs
import com.glooboo.umessage.helpers.cleanupPrefs
import com.glooboo.umessage.messaging.LongTextMms
import com.glooboo.umessage.helpers.composerPrefs
import com.glooboo.umessage.helpers.notifyPrefs
import com.glooboo.umessage.helpers.smartreply.SmartReplyPrefs
import com.glooboo.umessage.spam.SpamSettings
import com.glooboo.umessage.ui.lock.LockScope
import com.glooboo.umessage.ui.spam.CarrierReport

/**
 * N09: the settings added after the first backup format, read from and written to the phone. Split from
 * [SettingsBackup] to keep that object small. Left out on purpose, because they belong to this phone or are secrets:
 * the app-lock PIN and pattern, SIM ids and names, dismissed cards, one-time flags and the times of the last cleanup
 * and digest. Saved texts, hidden chats and mutes are not settings: they go in the messages backup's app data.
 */
internal object BackupMoreSettings {

    /** [base] with the later settings of the phone filled in; [spam] holds the ones kept with the spam settings. */
    fun collect(context: Context, base: BackupSettings, spam: SpamSettings): BackupSettings {
        val app = context.applicationContext
        val config = app.config
        val cleanup = app.cleanupPrefs
        val composer = app.composerPrefs
        val notify = app.notifyPrefs
        val w3c = W3cPrefs.get(app)
        return base.copy(
            unknownInboxNotify = spam.unknownInboxNotify,
            openLinkAction = spam.openLinkAction,
            blockOutsideRegion = spam.blockOutsideRegion,
            unreadFirst = w3c.unreadFirst,
            pinchZoomText = w3c.pinchZoomText,
            showTapbacks = w3c.showTapbacks,
            riskyLinksLocked = ThreadPrefs.get(app).riskyLinksLocked,
            bankCards = BankCardPrefs.get(app).cardsEnabled,
            codeCleanupDays = cleanup.codeDays,
            oldMessageDays = cleanup.oldMessageDays,
            weeklyDigest = cleanup.digestOn,
            carrierSpamNumber = CarrierReport(app).number,
            removePhotoLocation = composer.removePhotoLocation,
            voiceButton = composer.voiceButton,
            signature = composer.signature,
            chatSounds = composer.chatSounds,
            wakeScreen = notify.wakeScreen,
            chatBubbles = notify.chatBubbles,
            focusCodes = notify.focusCodes,
            copyCodeOnTap = notify.copyCodeOnTap,
            smartReplies = SmartReplyPrefs.get(app).enabled,
            lockScope = LockScope.current(app).key,
            showCharacterCounter = config.showCharacterCounter,
            useSimpleCharacters = config.useSimpleCharacters,
            deliveryReports = config.enableDeliveryReports,
            sendLongAsMms = config.sendLongAsMmsAfterParts != LongTextMms.NEVER,
            sendLongAsMmsAfter = config.sendLongAsMmsAfterParts,
            sendGroupAsMms = config.sendGroupMessageMMS,
            mmsFileSizeLimit = config.mmsFileSizeLimit,
            useRecycleBin = config.useRecycleBin,
            keepArchived = config.keepConversationsArchived,
            defaultSim = simChoice(config.defaultSimId),
            appFont = w3c.appFont.key,
        )
    }

    /** Writes what the file holds. The spam settings in it are applied by the caller, with the other spam settings. */
    fun apply(context: Context, s: BackupSettings) {
        val app = context.applicationContext
        applyDisplayAndCleanup(app, s)
        applyComposerAndNotify(app, s)
        applySending(app, s)
    }

    private fun applyDisplayAndCleanup(app: Context, s: BackupSettings) {
        val w3c = W3cPrefs.get(app)
        s.unreadFirst?.let { w3c.unreadFirst = it }
        s.pinchZoomText?.let { w3c.pinchZoomText = it }
        AppFont.entries.firstOrNull { it.key == s.appFont }?.let { w3c.appFont = it }
        s.showTapbacks?.let { w3c.showTapbacks = it }
        s.riskyLinksLocked?.let { ThreadPrefs.get(app).riskyLinksLocked = it }
        s.bankCards?.let { BankCardPrefs.get(app).cardsEnabled = it }
        // I4/Q38: the file may hold codeCleanupDays and oldMessageDays, but a restore never applies them: they start
        // the automatic move to the bin (and so the permanent purge), which the user must choose on this phone.
        s.weeklyDigest?.let { app.cleanupPrefs.digestOn = it }
        s.carrierSpamNumber?.let { CarrierReport(app).number = it }
    }

    private fun applyComposerAndNotify(app: Context, s: BackupSettings) {
        val composer = app.composerPrefs
        s.removePhotoLocation?.let { composer.removePhotoLocation = it }
        s.voiceButton?.let { composer.voiceButton = it }
        s.signature?.let { composer.signature = it }
        s.chatSounds?.let { composer.chatSounds = it }
        val notify = app.notifyPrefs
        s.wakeScreen?.let { notify.wakeScreen = it }
        s.chatBubbles?.let { notify.chatBubbles = it }
        s.focusCodes?.let { notify.focusCodes = it }
        s.copyCodeOnTap?.let { notify.copyCodeOnTap = it }
        s.smartReplies?.let { SmartReplyPrefs.get(app).enabled = it }
        s.lockScope?.let { LockScope.set(app, LockScope.fromKey(it)) }
    }

    private fun applySending(app: Context, s: BackupSettings) {
        val config = app.config
        s.showCharacterCounter?.let { config.showCharacterCounter = it }
        s.useSimpleCharacters?.let { config.useSimpleCharacters = it }
        s.deliveryReports?.let { config.enableDeliveryReports = it }
        // Q9: a file from before the choice only has the old switch: on meant "from 2 parts", off means never.
        val legacy = s.sendLongAsMms?.let { if (it) LongTextMms.THRESHOLDS.first() else LongTextMms.NEVER }
        (s.sendLongAsMmsAfter ?: legacy)?.let { config.sendLongAsMmsAfterParts = it }
        s.sendGroupAsMms?.let { config.sendGroupMessageMMS = it }
        s.mmsFileSizeLimit?.let { config.mmsFileSizeLimit = it }
        s.useRecycleBin?.let { config.useRecycleBin = it }
        s.keepArchived?.let { config.keepConversationsArchived = it }
        when (s.defaultSim) {
            BackupSettings.SIM_PHONE -> config.defaultSimId = DEFAULT_SIM_PHONE
            BackupSettings.SIM_ASK -> config.defaultSimId = DEFAULT_SIM_ASK
        }
    }

    /** "phone" or "ask"; null when a SIM is chosen, as its id is only meaningful on this phone. */
    internal fun simChoice(defaultSimId: Int): String? = when (defaultSimId) {
        DEFAULT_SIM_PHONE -> BackupSettings.SIM_PHONE
        DEFAULT_SIM_ASK -> BackupSettings.SIM_ASK
        else -> null
    }
}

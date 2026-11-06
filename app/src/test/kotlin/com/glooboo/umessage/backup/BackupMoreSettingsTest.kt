package com.glooboo.umessage.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.extensions.config
import com.glooboo.umessage.helpers.DEFAULT_SIM_ASK
import com.glooboo.umessage.helpers.DEFAULT_SIM_PHONE
import com.glooboo.umessage.helpers.FILE_SIZE_1_MB
import com.glooboo.umessage.helpers.composerPrefs
import com.glooboo.umessage.spam.SpamSettings
import com.glooboo.umessage.spam.UnknownInboxNotify
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** N09: the later settings are written to and read from the phone's preferences. */
@RunWith(AndroidJUnit4::class)
class BackupMoreSettingsTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val changed = BackupSettings(
        unknownInboxNotify = UnknownInboxNotify.SILENT, openLinkAction = true, blockOutsideRegion = "GB",
        unreadFirst = true, pinchZoomText = false, showTapbacks = false, riskyLinksLocked = true, bankCards = false,
        codeCleanupDays = 7, oldMessageDays = 90, weeklyDigest = false, carrierSpamNumber = "7726",
        removePhotoLocation = false, voiceButton = true, signature = "Sent from my phone", chatSounds = true,
        wakeScreen = true, chatBubbles = true, focusCodes = true, smartReplies = true, lockScope = "chosen",
        showCharacterCounter = true, useSimpleCharacters = true, deliveryReports = true, sendLongAsMms = true,
        sendGroupAsMms = true, mmsFileSizeLimit = FILE_SIZE_1_MB, useRecycleBin = true, keepArchived = true,
        defaultSim = BackupSettings.SIM_ASK, appFont = "system",
    )

    @Test fun appliedSettingsAreCollectedBack() {
        BackupMoreSettings.apply(context, changed)
        val spam = SpamSettings(
            unknownInboxNotify = UnknownInboxNotify.SILENT, openLinkAction = true, blockOutsideRegion = "GB",
        )
        assertThat(BackupMoreSettings.collect(context, BackupSettings(), spam)).isEqualTo(changed)
    }

    @Test fun missingSettingsLeaveThePhoneAsItIs() {
        BackupMoreSettings.apply(context, changed)
        BackupMoreSettings.apply(context, BackupSettings())
        assertThat(context.composerPrefs.signature).isEqualTo("Sent from my phone")
        assertThat(context.config.mmsFileSizeLimit).isEqualTo(FILE_SIZE_1_MB)
    }

    @Test fun aChosenSimIsNeverExported() {
        assertThat(BackupMoreSettings.simChoice(DEFAULT_SIM_PHONE)).isEqualTo(BackupSettings.SIM_PHONE)
        assertThat(BackupMoreSettings.simChoice(DEFAULT_SIM_ASK)).isEqualTo(BackupSettings.SIM_ASK)
        assertThat(BackupMoreSettings.simChoice(SOME_SIM_ID)).isNull()
        context.config.defaultSimId = SOME_SIM_ID
        val collected = BackupMoreSettings.collect(context, BackupSettings(), SpamSettings())
        assertThat(collected.defaultSim).isNull()
        BackupMoreSettings.apply(context, BackupSettings(defaultSim = BackupSettings.SIM_PHONE))
        assertThat(context.config.defaultSimId).isEqualTo(DEFAULT_SIM_PHONE)
    }

    private companion object {
        const val SOME_SIM_ID = 5
    }
}

package com.glooboo.umessage.backup

import com.glooboo.umessage.spam.ForwardRule
import com.glooboo.umessage.spam.ForwardSim
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamRule
import com.glooboo.umessage.spam.UnknownInboxNotify
import com.glooboo.umessage.spam.UnknownSenderMode
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

private const val LATER_SETTING_COUNT = 31

class SettingsBackupFormatTest {
    private val rules = listOf(
        SpamRule(kind = ListKind.BLOCK, type = RuleType.KEYWORD, pattern = "تخفیف", note = "ads", createdAt = 5),
        SpamRule(kind = ListKind.ALLOW, type = RuleType.NUMBER, pattern = "+447700900155", createdAt = 6),
    )
    private val forward = ForwardRule(
        senderType = RuleType.NUMBER, senderPattern = "+447121234567",
        bodyType = RuleType.KEYWORD, bodyPattern = "bank",
        windowStart = 480, windowEnd = 1200, subscriptionId = 7, targets = listOf("+15550001111"),
        sendFrom = ForwardSim.SIM2, includeCodes = true, enabled = false, note = "dad", createdAt = 9,
    )
    private val settings = BackupSettings(
        spamMode = UnknownSenderMode.STRICT, purgeAfterDays = 14, showForwardedTab = true,
        dailyForwardLimit = 30, maskForwardedCodes = true, copyCodeOnTap = false,
    )

    @Test fun roundTrip() {
        val text = SettingsBackupFormat.encode(BackupContent(rules, listOf(forward), settings), 123)
        val back = SettingsBackupFormat.decode(text)
        assertThat(back.rules).isEqualTo(rules)
        // The SIM id is not kept; everything else is.
        assertThat(back.forwardRules).containsExactly(forward.copy(subscriptionId = null))
        assertThat(back.settings).isEqualTo(settings)
    }

    @Test fun containsNoLockData() {
        val text = SettingsBackupFormat.encode(BackupContent(rules, listOf(forward), settings), 1).lowercase()
        assertThat(text).doesNotContain("pin")
        // "hideCodesOnLockScreen" is a notification setting; what must never be written is app-lock data.
        assertThat(text).doesNotContain("applock")
        assertThat(text).doesNotContain("passcode")
        assertThat(text).doesNotContain("pattern_hash")
    }

    @Test fun unknownFieldsAndEntriesAreTolerated() {
        val text = """
            {"app":"spamless","kind":"settings-backup","version":9,"future":{"a":1},
             "rules":[{"kind":"BLOCK","type":"KEYWORD","pattern":"spam","extra":true},
                      {"kind":"MAYBE","type":"KEYWORD","pattern":"x"},
                      {"kind":"BLOCK","type":"REGEX","pattern":"("},
                      {"kind":"BLOCK","type":"KEYWORD","pattern":""}, 5],
             "forwardRules":[{"senderType":"NUMBER","senderPattern":"123","targets":[]}],
             "settings":{"spamMode":"NOPE","purgeAfterDays":"x","useJalaliDates":true}}
        """.trimIndent()
        val back = SettingsBackupFormat.decode(text)
        assertThat(back.rules.map { it.pattern }).containsExactly("spam")
        assertThat(back.forwardRules).isEmpty()
        assertThat(back.settings).isEqualTo(BackupSettings(useJalaliDates = true))
    }

    @Test fun otherJsonIsRejected() {
        assertThrows(BackupFormatException::class.java) { SettingsBackupFormat.decode("{\"hello\":1}") }
        assertThrows(BackupFormatException::class.java) { SettingsBackupFormat.decode("not json") }
        assertThrows(BackupFormatException::class.java) { SettingsBackupFormat.decode("[1,2]") }
    }

    @Test fun forwardKeyIgnoresOrderAndCase() {
        val a = forward.copy(targets = listOf("+1", "+2"), senderPattern = "ABC")
        val b = forward.copy(targets = listOf("+2", "+1"), senderPattern = "abc", enabled = true)
        assertThat(SettingsBackupFormat.forwardKey(a)).isEqualTo(SettingsBackupFormat.forwardKey(b))
    }

    // B2 / L2: display, Enter key, lock-screen privacy and the pause are part of the file.
    @Test fun displaySettingsRoundTrip() {
        val display = BackupSettings(
            useJalaliDates = true, dateStyle = "both", textSize = "largest", theme = "SKY", sendOnEnter = true,
            lockScreenVisibility = 2, forwardPaused = true,
        )
        val text = SettingsBackupFormat.encode(BackupContent(emptyList(), emptyList(), display), 1)
        assertThat(SettingsBackupFormat.decode(text).settings).isEqualTo(display)
    }

    @Test fun everyTextSizeKeyRoundTrips() {
        listOf("xsmall", "small", "default", "large", "largest", "huge", "giant").forEach { key ->
            val settings = BackupSettings(textSize = key)
            val text = SettingsBackupFormat.encode(BackupContent(emptyList(), emptyList(), settings), 1)
            assertThat(SettingsBackupFormat.decode(text).settings.textSize).isEqualTo(key)
        }
    }

    @Test fun appFontKeysRoundTripAndUnknownOnesAreDropped() {
        listOf("umessage", "system").forEach { key ->
            val content = BackupContent(emptyList(), emptyList(), BackupSettings(appFont = key))
            val text = SettingsBackupFormat.encode(content, 1)
            assertThat(SettingsBackupFormat.decode(text).settings.appFont).isEqualTo(key)
        }
        val unknown = """{"app":"spamless","kind":"settings-backup","version":1,"settings":{"appFont":"comic"}}"""
        assertThat(SettingsBackupFormat.decode(unknown).settings.appFont).isNull()
    }

    @Test fun unknownDisplayValuesAreDropped() {
        val text = """
            {"app":"spamless","kind":"settings-backup","version":1,
             "settings":{"dateStyle":"martian","textSize":"enormous","theme":"x;y",
                         "lockScreenVisibility":9,"sendOnEnter":"yes"}}
        """.trimIndent()
        assertThat(SettingsBackupFormat.decode(text).settings).isEqualTo(BackupSettings())
    }

    // L3: the time window, codes and sending SIM make a different rule; a local and an international target do not.
    @Test fun forwardKeyCoversWindowCodesAndSim() {
        val key = SettingsBackupFormat::forwardKey
        assertThat(key(forward.copy(windowStart = null, windowEnd = null))).isNotEqualTo(key(forward))
        assertThat(key(forward.copy(includeCodes = false))).isNotEqualTo(key(forward))
        assertThat(key(forward.copy(sendFrom = ForwardSim.SAME))).isNotEqualTo(key(forward))
        assertThat(key(forward.copy(targets = listOf("07121112233"))))
            .isEqualTo(key(forward.copy(targets = listOf("+447121112233"))))
    }

    // H1: imported forward rules never start forwarding and only keep targets the editor would accept.
    @Test fun importedForwardRulesAreSwitchedOffWithCheckedTargets() {
        val incoming = forward.copy(
            enabled = true, subscriptionId = 4,
            targets = listOf("09000001111", "1234", "+15550002222", "+447121234567"),
        )
        val own = listOf("+15550002222")
        val result = SettingsBackupFormat.prepareForwardImport(listOf(incoming), emptyList(), ownNumbers = own)
        val added = result.toAdd.single()
        assertThat(added.enabled).isFalse()
        assertThat(added.subscriptionId).isNull()
        // 09… is stored with its country code; a short code, the phone's own number and the watched sender
        // are dropped.
        assertThat(added.targets).containsExactly("+989000001111")
        assertThat(result.unusable).isEqualTo(0)
    }

    @Test fun forwardRuleWithoutUsableTargetIsDropped() {
        val noTarget = forward.copy(targets = listOf("1234", "abc"))
        val result = SettingsBackupFormat.prepareForwardImport(listOf(noTarget), emptyList())
        assertThat(result.toAdd).isEmpty()
        assertThat(result.unusable).isEqualTo(1)
    }

    @Test fun forwardRulesAlreadyOnThePhoneAreSkipped() {
        val onPhone = forward.copy(id = 3, targets = listOf("+15550001111"), enabled = true)
        val result = SettingsBackupFormat.prepareForwardImport(listOf(forward, forward), listOf(onPhone))
        assertThat(result.toAdd).isEmpty()
        assertThat(result.duplicates).isEqualTo(2)
    }

    // N09: every later setting survives a write and a read.
    @Test fun laterSettingsRoundTrip() {
        val later = BackupSettings(
            unknownInboxNotify = UnknownInboxNotify.NONE, openLinkAction = true, blockOutsideRegion = "GB",
            unreadFirst = true, pinchZoomText = false, showTapbacks = false, riskyLinksLocked = true, bankCards = false,
            codeCleanupDays = 7, oldMessageDays = 90, weeklyDigest = false, carrierSpamNumber = "7726",
            removePhotoLocation = false, voiceButton = true, signature = "Sent from my phone", chatSounds = true,
            wakeScreen = true, chatBubbles = true, focusCodes = true, smartReplies = true, lockScope = "chosen",
            showCharacterCounter = true, useSimpleCharacters = true, deliveryReports = true, sendLongAsMms = true,
            sendLongAsMmsAfter = 4, sendGroupAsMms = true, mmsFileSizeLimit = 1_048_576L, useRecycleBin = true,
            keepArchived = true,
            defaultSim = "ask",
        )
        val text = SettingsBackupFormat.encode(BackupContent(emptyList(), emptyList(), later), 1)
        assertThat(SettingsBackupFormat.decode(text).settings).isEqualTo(later)
        val set = later.values().filterValues { it != null }
        set.keys.forEach { assertThat(text).contains("\"$it\"") }
        assertThat(set).hasSize(LATER_SETTING_COUNT)
    }

    @Test fun noLimitAndEmptyValuesRoundTrip() {
        val cleared = BackupSettings(
            mmsFileSizeLimit = -1L, blockOutsideRegion = "", carrierSpamNumber = "", signature = "",
        )
        val text = SettingsBackupFormat.encode(BackupContent(emptyList(), emptyList(), cleared), 1)
        assertThat(SettingsBackupFormat.decode(text).settings).isEqualTo(cleared)
    }

    @Test fun wrongTypesAndOddValuesOfLaterSettingsAreDropped() {
        val text = """
            {"app":"spamless","kind":"settings-backup","version":1,
             "settings":{"unknownInboxNotify":"LOUD","openLinkAction":"yes","blockOutsideRegion":"nowhere",
                         "codeCleanupDays":99999,"carrierSpamNumber":"77x6","lockScope":"everything",
                         "mmsFileSizeLimit":12345,"defaultSim":"3","sendLongAsMms":1,"signature":5}}
        """.trimIndent()
        assertThat(SettingsBackupFormat.decode(text).settings).isEqualTo(BackupSettings())
    }

    @Test fun aSimIdIsNotAcceptedAsDefaultSim() {
        val text = """{"app":"spamless","kind":"settings-backup","version":1,"settings":{"defaultSim":"2"}}"""
        assertThat(SettingsBackupFormat.decode(text).settings.defaultSim).isNull()
    }

    @Test fun fileWrittenBeforeWave1StillRestores() {
        val text = """
            {"app":"spamless","kind":"settings-backup","version":1,"exportedAt":1730000000000,
             "rules":[{"kind":"BLOCK","type":"KEYWORD","pattern":"promo","note":"","createdAt":1}],
             "forwardRules":[],
             "settings":{"spamMode":"STRICT","otpExemption":true,"purgeAfterDays":30,"notifyOnBlock":false,
                         "showForwardedTab":true,"useJalaliDates":false,"textSize":"large","sendOnEnter":true,
                         "lockScreenVisibility":1,"forwardPaused":false}}
        """.trimIndent()
        val back = SettingsBackupFormat.decode(text)
        assertThat(back.rules.map { it.pattern }).containsExactly("promo")
        assertThat(back.appVersion).isNull()
        assertThat(back.settings.spamMode).isEqualTo(UnknownSenderMode.STRICT)
        assertThat(back.settings.textSize).isEqualTo("large")
        // Settings the old file does not know are "not in the file", so a restore leaves them alone.
        val known = setOf(
            BackupSettings.SPAM_MODE, BackupSettings.PURGE_DAYS,
            BackupSettings.NOTIFY_ON_BLOCK, BackupSettings.SHOW_FORWARDED_TAB, BackupSettings.JALALI,
            BackupSettings.TEXT_SIZE, BackupSettings.SEND_ON_ENTER, BackupSettings.LOCK_SCREEN,
            BackupSettings.FORWARD_PAUSED,
        )
        val present = back.settings.values().filterValues { it != null }.keys
        assertThat(present).containsExactlyElementsIn(known)
    }

    // The old "codes from unknown senders" setting is no longer written or applied; a file that has it still loads.
    @Test fun theOldCodesSettingIsNotWrittenButAnOldFileWithItStillLoads() {
        val written = SettingsBackupFormat.encode(BackupContent(emptyList(), emptyList(), settings), 1)
        assertThat(written).doesNotContain(BackupSettings.OTP_EXEMPTION)

        val old = """{"app":"spamless","kind":"settings-backup","version":1,
            "settings":{"spamMode":"STRICT","otpExemption":false,"textSize":"large"}}"""
        val back = SettingsBackupFormat.decode(old).settings
        assertThat(back.spamMode).isEqualTo(UnknownSenderMode.STRICT)
        assertThat(back.textSize).isEqualTo("large")
        assertThat(back.values().keys).doesNotContain(BackupSettings.OTP_EXEMPTION)
    }

    @Test fun copyCodeOnTapRoundTripsAndOldFilesLeaveItAlone() {
        val off = BackupSettings(copyCodeOnTap = false)
        val text = SettingsBackupFormat.encode(BackupContent(emptyList(), emptyList(), off), 1)
        assertThat(text).contains("\"copyCodeOnTap\": false")
        assertThat(SettingsBackupFormat.decode(text).settings.copyCodeOnTap).isFalse()

        // A file from before 0.4.5 does not have it: null, so a restore keeps the phone's setting (on by default).
        val old = """{"app":"spamless","kind":"settings-backup","version":1,"settings":{"focusCodes":true}}"""
        assertThat(SettingsBackupFormat.decode(old).settings.copyCodeOnTap).isNull()
        assertThat(SettingsBackupFormat.changedSettings(BackupSettings(copyCodeOnTap = true), off))
            .containsExactly(BackupSettings.COPY_CODE_ON_TAP)
    }

    @Test fun appVersionIsWrittenAsInfoAndRead() {
        val text = SettingsBackupFormat.encode(BackupContent(emptyList(), emptyList(), BackupSettings(), "1.2.3"), 1)
        assertThat(SettingsBackupFormat.decode(text).appVersion).isEqualTo("1.2.3")
    }

    @Test fun laterSettingsAreListedWhenTheyDiffer() {
        val changed = SettingsBackupFormat.changedSettings(
            BackupSettings(wakeScreen = false, signature = ""),
            BackupSettings(wakeScreen = true, signature = ""),
        )
        assertThat(changed).containsExactly(BackupSettings.WAKE_SCREEN)
    }

    @Test fun nothingPersonalOrDeviceSpecificIsInTheFile() {
        val names = BackupSettings().values().keys.joinToString(" ").lowercase()
        listOf("hidden", "mute", "savedtext", "simname", "thread", "hash", "passcode", "applock").forEach {
            assertThat(names).doesNotContain(it)
        }
    }

    @Test fun changedSettingsListsOnlyDifferences() {
        val current = BackupSettings(spamMode = UnknownSenderMode.SMART, textSize = "default", forwardPaused = true)
        val incoming = BackupSettings(spamMode = UnknownSenderMode.SMART, textSize = "large", forwardPaused = false)
        assertThat(SettingsBackupFormat.changedSettings(current, incoming)).containsExactly(BackupSettings.TEXT_SIZE)
        val pause = SettingsBackupFormat.changedSettings(
            BackupSettings(forwardPaused = false),
            BackupSettings(forwardPaused = true),
        )
        assertThat(pause).containsExactly(BackupSettings.FORWARD_PAUSED)
    }
}

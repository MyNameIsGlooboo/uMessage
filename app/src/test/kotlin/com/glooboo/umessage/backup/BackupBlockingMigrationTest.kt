package com.glooboo.umessage.backup

import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamRule
import com.glooboo.umessage.spam.UnknownSenderMode
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Restoring an older backup: the old blocked words and the unknown-senders mode end up as the migration does. */
class BackupBlockingMigrationTest {

    private fun keywordRule(word: String, sim: Int? = null) =
        SpamRule(kind = ListKind.BLOCK, type = RuleType.KEYWORD, pattern = word, subscriptionId = sim)

    @Test
    fun onlyBothSimKeywordRulesCountAsTheOldWordsAlreadyBeingRules() {
        val rules = listOf(
            keywordRule("prize"),
            keywordRule("one sim", sim = 1),
            SpamRule(kind = ListKind.ALLOW, type = RuleType.KEYWORD, pattern = "allowed"),
            SpamRule(kind = ListKind.BLOCK, type = RuleType.PREFIX, pattern = "prefix"),
        )
        assertThat(SettingsBackup.blockKeywordRules(rules)).containsExactly("prize")
        // The preview's count: a word with a rule for one SIM only is still new.
        val fresh = SettingsBackupFormat.newKeywords(
            listOf("prize", "one sim"), SettingsBackup.blockKeywordRules(rules).toSet(),
        )
        assertThat(fresh).containsExactly("one sim")
    }

    @Test
    fun anOlderBackupWithOffRestoresAsStrictWhenThePhoneHadTheOldSwitchOn() {
        val mode = SettingsBackup.restoredMode(
            UnknownSenderMode.OFF, UnknownSenderMode.SMART, fileCountsSwitch = false, phoneSwitchWasOn = true,
        )
        assertThat(mode).isEqualTo(UnknownSenderMode.STRICT)
    }

    @Test
    fun anOlderBackupKeepsItsModeWhenThePhoneNeverHadTheSwitchOn() {
        for (incoming in UnknownSenderMode.entries) {
            val mode = SettingsBackup.restoredMode(
                incoming, UnknownSenderMode.SMART, fileCountsSwitch = false, phoneSwitchWasOn = false,
            )
            assertThat(mode).isEqualTo(incoming)
        }
    }

    @Test
    fun anOlderBackupWithSmartOrStrictIsNotChangedBySwitch() {
        for (incoming in listOf(UnknownSenderMode.SMART, UnknownSenderMode.STRICT)) {
            val mode = SettingsBackup.restoredMode(
                incoming, UnknownSenderMode.OFF, fileCountsSwitch = false, phoneSwitchWasOn = true,
            )
            assertThat(mode).isEqualTo(incoming)
        }
    }

    @Test
    fun aNewBackupWithOffRestoresAsOff() {
        val mode = SettingsBackup.restoredMode(
            UnknownSenderMode.OFF, UnknownSenderMode.SMART, fileCountsSwitch = true, phoneSwitchWasOn = true,
        )
        assertThat(mode).isEqualTo(UnknownSenderMode.OFF)
    }

    @Test
    fun aBackupWithoutAModeLeavesTheCurrentOne() {
        val mode = SettingsBackup.restoredMode(
            null, UnknownSenderMode.STRICT, fileCountsSwitch = false, phoneSwitchWasOn = true,
        )
        assertThat(mode).isEqualTo(UnknownSenderMode.STRICT)
    }

    @Test
    fun theFileMarkerRoundTripsAndOlderFilesLackIt() {
        val content = BackupContent(emptyList(), emptyList(), BackupSettings())
        val text = SettingsBackupFormat.encode(content, 1)
        assertThat(SettingsBackupFormat.decode(text).modeIncludesSwitch).isTrue()
        val old = """{"app":"spamless","kind":"settings-backup","version":1}"""
        assertThat(SettingsBackupFormat.decode(old).modeIncludesSwitch).isFalse()
    }

    @Test
    fun theModeAFileRoundTripsWithIsTheOneARestoreApplies() {
        val content = BackupContent(
            emptyList(), emptyList(),
            BackupSettings(spamMode = UnknownSenderMode.STRICT),
        )
        val back = SettingsBackupFormat.decode(SettingsBackupFormat.encode(content, 1))
        assertThat(back.settings.spamMode).isEqualTo(UnknownSenderMode.STRICT)
        val applied = SettingsBackup.restoredSettings(
            back.settings, UnknownSenderMode.SMART, back.modeIncludesSwitch, true,
        )
        assertThat(applied.spamMode).isEqualTo(UnknownSenderMode.STRICT)
    }

    @Test
    fun thePreviewComparesTheModeARestoreWouldApply() {
        // An older file with Off, on a phone that had the switch on, restores as Strict: no change on a Strict phone.
        val file = BackupSettings(spamMode = UnknownSenderMode.OFF)
        val applied = SettingsBackup.restoredSettings(file, UnknownSenderMode.STRICT, false, true)
        assertThat(applied.spamMode).isEqualTo(UnknownSenderMode.STRICT)
        val noMode = SettingsBackup.restoredSettings(BackupSettings(), UnknownSenderMode.STRICT, false, true)
        assertThat(noMode.spamMode).isNull()
    }
}

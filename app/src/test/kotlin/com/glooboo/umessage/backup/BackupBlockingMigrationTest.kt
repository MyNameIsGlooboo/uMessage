package com.glooboo.umessage.backup

import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamRule
import com.glooboo.umessage.spam.UnknownSenderMode
import com.glooboo.umessage.ui.spam.BlockingMigration
import com.glooboo.umessage.ui.spam.BlockingMigrationStore
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** Restoring an older backup: the old blocked words and the unknown-senders mode end up as the migration does. */
class BackupBlockingMigrationTest {

    private fun keywordRule(word: String) = SpamRule(kind = ListKind.BLOCK, type = RuleType.KEYWORD, pattern = word)

    private fun addIfNew(rules: MutableList<SpamRule>, rule: SpamRule) {
        if (rules.none { it.type == rule.type && it.pattern == rule.pattern }) rules += rule
    }

    private class Words(var words: Set<String>) : BlockingMigrationStore {
        override fun keywords() = words
        override fun removeKeywords(words: Collection<String>) {
            this.words = this.words - words.toSet()
        }

        override fun blockUnknownSwitch() = false
        override fun clearBlockUnknownSwitch() = Unit
    }

    @Test
    fun restoredWordsBecomeRulesWithoutDuplicatesAndTheCountsAreRight() = runBlocking {
        // The file holds four words and a rule for one of them; the phone already has a rule for another.
        val fileWords = listOf("prize", "win now", "free gift", "x")
        val fileRules = listOf(keywordRule("prize"))
        val phoneRules = mutableListOf(keywordRule("win now"))

        // Preview: words that are rules (on the phone or in the file) are not new; "free gift" and "x" are.
        val ruleWords = (phoneRules + fileRules).map { it.pattern }
        val preview = SettingsBackupFormat.newKeywords(fileWords, emptySet<String>() + ruleWords)
        assertThat(preview).containsExactly("free gift", "x")

        // Apply: the file's rules are added (the one on the phone already is skipped), then the new words.
        val (freshRules, present) = SettingsBackupFormat.newRules(fileRules, phoneRules)
        phoneRules += freshRules
        assertThat(freshRules).hasSize(1)
        assertThat(present).isEqualTo(0)
        val store = Words(preview.toSet())

        // The migration turns what can be a rule into one; "x" (one letter) cannot and stays on the old list.
        val result = BlockingMigration.run(
            store = store,
            existingRules = { phoneRules.toList() },
            addRule = { rule -> addIfNew(phoneRules, rule) },
            updateMode = { },
        )
        assertThat(phoneRules.map { it.pattern }).containsExactly("win now", "prize", "free gift")
        assertThat(store.words).containsExactly("x")
        assertThat(result.wordsMoved).isEqualTo(1)
        assertThat(result.wordsKept).isEqualTo(1)
    }

    @Test
    fun aRestoredWordThatIsAlreadyARuleIsDroppedWithoutAddingASecondRule() = runBlocking {
        val phoneRules = mutableListOf(keywordRule("prize"))
        val store = Words(setOf("prize", "free gift"))
        val result = BlockingMigration.run(
            store = store,
            existingRules = { phoneRules.toList() },
            addRule = { rule -> addIfNew(phoneRules, rule) },
            updateMode = { },
        )
        assertThat(phoneRules.map { it.pattern }).containsExactly("prize", "free gift")
        assertThat(store.words).isEmpty()
        assertThat(result.wordsMoved).isEqualTo(2)
        assertThat(result.wordsKept).isEqualTo(0)
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
    fun anExportStatesWhatThePhoneActsAs() {
        assertThat(SettingsBackup.exportedMode(UnknownSenderMode.OFF, true)).isEqualTo(UnknownSenderMode.STRICT)
        assertThat(SettingsBackup.exportedMode(UnknownSenderMode.OFF, false)).isEqualTo(UnknownSenderMode.OFF)
        assertThat(SettingsBackup.exportedMode(UnknownSenderMode.SMART, true)).isEqualTo(UnknownSenderMode.SMART)
    }

    @Test
    fun theModeAFileRoundTripsWithIsTheOneARestoreApplies() {
        val content = BackupContent(
            emptyList(), emptyList(),
            BackupSettings(spamMode = SettingsBackup.exportedMode(UnknownSenderMode.OFF, true)),
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

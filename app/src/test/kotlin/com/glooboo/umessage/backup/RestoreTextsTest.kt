package com.glooboo.umessage.backup

import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamRule
import com.glooboo.umessage.spam.UnknownSenderMode
import com.glooboo.umessage.ui.settings.RestoreSummary
import com.glooboo.umessage.ui.settings.RestoreSummary.After
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** J17: the restore preview and result texts say only what happens. */
class RestoreTextsTest {
    private fun number(pattern: String, sim: Int? = null) =
        SpamRule(kind = ListKind.BLOCK, type = RuleType.NUMBER, pattern = pattern, subscriptionId = sim, createdAt = 1)

    @Test fun purgeDaysAreNotListedBecauseARestoreNeverAppliesThem() {
        val changed = SettingsBackupFormat.changedSettings(
            BackupSettings(purgeAfterDays = 30),
            BackupSettings(purgeAfterDays = 7),
        )
        assertThat(changed).isEmpty()
    }

    @Test fun strictAndSmartAreTheSameOn() {
        val changed = SettingsBackupFormat.changedSettings(
            BackupSettings(spamMode = UnknownSenderMode.SMART),
            BackupSettings(spamMode = UnknownSenderMode.STRICT),
        )
        assertThat(changed).isEmpty()
    }

    @Test fun onAndOffModesAreAChange() {
        val changed = SettingsBackupFormat.changedSettings(
            BackupSettings(spamMode = UnknownSenderMode.OFF),
            BackupSettings(spamMode = UnknownSenderMode.STRICT),
        )
        assertThat(changed).containsExactly(BackupSettings.SPAM_MODE)
    }

    @Test fun aRuleRepeatedInTheFileIsNotAlreadyOnThePhone() {
        val (fresh, present) = SettingsBackupFormat.newRules(
            incoming = listOf(number("07700 900123"), number("07700 900123")),
            existing = emptyList(),
        )
        assertThat(fresh).hasSize(1)
        assertThat(present).isEqualTo(0)
    }

    @Test fun nothingToDoAfterwardsWhenDefaultAndNoLockInTheFile() {
        assertThat(RestoreSummary.afterRestore(BackupSettings(), notDefaultNow = false, lockOnNow = false))
            .isEqualTo(After.NONE)
    }

    @Test fun defaultAppPartOnlyWhenNotDefault() {
        assertThat(RestoreSummary.afterRestore(BackupSettings(), notDefaultNow = true, lockOnNow = false))
            .isEqualTo(After.DEFAULT_APP)
    }

    @Test fun lockPartOnlyWhenTheFileHadALockAndNoneIsSetNow() {
        val locked = BackupSettings(lockScope = "chosen")
        assertThat(RestoreSummary.afterRestore(locked, notDefaultNow = false, lockOnNow = false)).isEqualTo(After.LOCK)
        assertThat(RestoreSummary.afterRestore(locked, notDefaultNow = false, lockOnNow = true)).isEqualTo(After.NONE)
        assertThat(RestoreSummary.afterRestore(locked, notDefaultNow = true, lockOnNow = false)).isEqualTo(After.BOTH)
    }
}

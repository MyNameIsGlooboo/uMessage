package com.glooboo.umessage.backup

import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamRule
import com.glooboo.umessage.messaging.LongTextMms
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

    @Test fun defaultAppPartOnlyWhenNotDefault() {
        assertThat(RestoreSummary.afterRestore(notDefaultNow = true, lockOnNow = true)).isEqualTo(After.DEFAULT_APP)
    }

    @Test fun lockPartWhenNoLockIsOnNow() {
        assertThat(RestoreSummary.afterRestore(notDefaultNow = false, lockOnNow = false)).isEqualTo(After.LOCK)
        assertThat(RestoreSummary.afterRestore(notDefaultNow = true, lockOnNow = false)).isEqualTo(After.BOTH)
    }

    @Test fun noLockPartWhenALockIsOnNow() {
        assertThat(RestoreSummary.afterRestore(notDefaultNow = false, lockOnNow = true)).isEqualTo(After.NONE)
    }

    @Test fun theSameNumberOnTwoSimsIsOneNewRule() {
        val (fresh, present) = SettingsBackupFormat.newRules(
            incoming = listOf(number("07700 900123", 1), number("07700 900123", 2)),
            existing = emptyList(),
        )
        assertThat(fresh).hasSize(1)
        assertThat(present).isEqualTo(0)
    }

    @Test fun cleanupDaysAreNotListedEither() {
        val changed = SettingsBackupFormat.changedSettings(
            BackupSettings(codeCleanupDays = 7, oldMessageDays = 30),
            BackupSettings(codeCleanupDays = 14, oldMessageDays = 90),
        )
        assertThat(changed).isEmpty()
    }

    @Test fun everySettingKeyIsLabelledOrNeverApplied() {
        val oneTimeFlags = setOf(BackupSettings.ARCHIVED_INTRO)
        val unnamed = BackupSettings().values().keys.filter {
            RestoreSummary.labelOf(it) == null && it !in BackupSettings.NOT_APPLIED && it !in oneTimeFlags
        }
        assertThat(unnamed).isEmpty()
    }

    @Test fun aFontOnlyDifferenceHasALabel() {
        val changed = SettingsBackupFormat.changedSettings(
            BackupSettings(appFont = "umessage"),
            BackupSettings(appFont = "system"),
        )
        assertThat(changed).containsExactly(BackupSettings.APP_FONT)
        assertThat(RestoreSummary.labelOf(BackupSettings.APP_FONT)).isNotNull()
    }

    @Test fun theOldLongMessageSwitchIsMappedLikeTheApplyDoes() {
        val on = SettingsBackup.restoredSettings(
            BackupSettings(sendLongAsMms = true), UnknownSenderMode.SMART, true, false,
        )
        assertThat(on.sendLongAsMmsAfter).isEqualTo(LongTextMms.THRESHOLDS.first())
        val off = SettingsBackup.restoredSettings(
            BackupSettings(sendLongAsMms = false), UnknownSenderMode.SMART, true, false,
        )
        assertThat(off.sendLongAsMmsAfter).isEqualTo(LongTextMms.NEVER)
        val newer = SettingsBackup.restoredSettings(
            BackupSettings(sendLongAsMms = true, sendLongAsMmsAfter = 5), UnknownSenderMode.SMART, true, false,
        )
        assertThat(newer.sendLongAsMmsAfter).isEqualTo(5)
    }

    @Test fun bothAfterPartsAreTwoParagraphsInOrder() {
        assertThat(RestoreSummary.afterParts(After.BOTH)).hasSize(2)
        assertThat(RestoreSummary.afterParts(After.NONE)).isEmpty()
        assertThat(RestoreSummary.afterParts(After.LOCK)).hasSize(1)
    }
}

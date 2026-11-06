package com.glooboo.umessage.ui.settings

import com.glooboo.umessage.backup.BackupContent
import com.glooboo.umessage.backup.BackupSettings
import com.glooboo.umessage.backup.ForwardImport
import com.glooboo.umessage.backup.SettingsBackup
import com.glooboo.umessage.spam.ForwardRule
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** B16: the in-app language choice, and B3: the restore summary text. */
@RunWith(AndroidJUnit4::class)
class AppLanguageTest {

    @Test
    fun storedTagsMapToAChoice() {
        assertThat(AppLanguage.fromTags("")).isEqualTo(AppLanguage.SYSTEM)
        assertThat(AppLanguage.fromTags("fa")).isEqualTo(AppLanguage.PERSIAN)
        assertThat(AppLanguage.fromTags("en-US,fa")).isEqualTo(AppLanguage.ENGLISH)
        assertThat(AppLanguage.fromTags("sv")).isEqualTo(AppLanguage.SYSTEM)
    }

    @Test
    fun restoreSummarySaysForwardRulesComeBackOff() {
        val preview = SettingsBackup.Preview(
            content = BackupContent(
                rules = listOf(
                    SpamRule(kind = ListKind.BLOCK, type = RuleType.KEYWORD, pattern = "x"),
                    SpamRule(kind = ListKind.ALLOW, type = RuleType.NUMBER, pattern = "+15550001111"),
                ),
                forwardRules = emptyList(),
                settings = BackupSettings(),
            ),
            forwards = ForwardImport(
                listOf(ForwardRule(targets = listOf("+15550001111"))),
                duplicates = 1,
                unusable = 1,
            ),
            changedSettings = listOf(
                BackupSettings.TEXT_SIZE, BackupSettings.DATE_STYLE,
                BackupSettings.JALALI, BackupSettings.ARCHIVED_INTRO,
            ),
        )
        val text = RestoreSummary.lines(ApplicationProvider.getApplicationContext(), preview)
        assertThat(text).contains("Allow rules: 1 · Block rules: 1")
        assertThat(text).contains("Forward rules to add: 1, switched off")
        assertThat(text).contains("Forward rules skipped: 2")
        // Dates once, and the internal flag not at all.
        assertThat(text).contains("Settings that change: Text size, Dates")
    }
}

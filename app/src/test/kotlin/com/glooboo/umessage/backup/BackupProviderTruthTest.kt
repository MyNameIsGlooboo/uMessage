package com.glooboo.umessage.backup

import com.glooboo.umessage.spam.AllowGroup
import com.glooboo.umessage.spam.AllowGroupMember
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.MemberSource
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamRule
import com.glooboo.umessage.spam.UnknownSenderMode
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Settings backups and provider truth (7.1, 7.2): old files still import and map their mode to the filter switch; new
 * files carry the switch, both group switches, the group members and the old mode field for older app versions; a
 * rule's SIM is never written and never read. Every number is made up.
 */
class BackupProviderTruthTest {

    private fun restored(file: BackupSettings, current: UnknownSenderMode = UnknownSenderMode.SMART) =
        SettingsBackup.restoredSettings(file, current, fileCountsSwitch = true, phoneSwitchWasOn = false)

    @Test fun anOldFileMapsItsModeToTheSwitch() {
        assertThat(restored(BackupSettings(spamMode = UnknownSenderMode.SMART)).filterOn).isTrue()
        assertThat(restored(BackupSettings(spamMode = UnknownSenderMode.STRICT)).filterOn).isTrue()
        assertThat(restored(BackupSettings(spamMode = UnknownSenderMode.OFF)).filterOn).isFalse()
        // An older file whose Off acted as Strict on a phone with the old switch on restores as on.
        val old = SettingsBackup.restoredSettings(
            BackupSettings(spamMode = UnknownSenderMode.OFF), UnknownSenderMode.SMART,
            fileCountsSwitch = false, phoneSwitchWasOn = true,
        )
        assertThat(old.filterOn).isTrue()
        // No mode, no switch: the phone's setting stays.
        assertThat(restored(BackupSettings(textSize = "large")).filterOn).isNull()
    }

    @Test fun aNewFileSaysItsSwitch() {
        val file = BackupSettings(spamMode = UnknownSenderMode.SMART, filterOn = false)
        assertThat(restored(file).filterOn).isFalse()
        assertThat(SettingsBackup.restoredFilterOn(file, UnknownSenderMode.SMART, current = true)).isFalse()
        assertThat(SettingsBackup.restoredFilterOn(BackupSettings(), UnknownSenderMode.OFF, current = true)).isTrue()
        assertThat(
            SettingsBackup.restoredFilterOn(
                BackupSettings(spamMode = UnknownSenderMode.OFF), UnknownSenderMode.OFF, current = true,
            )
        ).isFalse()
    }

    @Test fun aRealOldFileStillImports() {
        val old = """{"app":"spamless","kind":"settings-backup","version":1,"modeIncludesSwitch":true,
            "rules":[{"kind":"BLOCK","type":"KEYWORD","pattern":"prize","subscriptionId":2,"note":"","createdAt":5}],
            "settings":{"spamMode":"STRICT","purgeAfterDays":30,"notifyOnBlock":true}}"""
        val content = SettingsBackupFormat.decode(old)
        assertThat(content.rules.single().pattern).isEqualTo("prize")
        assertThat(content.rules.single().subscriptionId).isNull()
        assertThat(content.groupMembers).isEmpty()
        assertThat(content.settings.filterOn).isNull()
        assertThat(content.settings.notifyOnBlock).isTrue()
        assertThat(restored(content.settings).filterOn).isTrue()
    }

    @Test fun switchesAndMembersRoundTripAndNoSimIsWritten() {
        val members = listOf(
            AllowGroupMember(AllowGroup.CODE, "+15550001234", "+15550001234", 10L, MemberSource.LIVE, false),
            AllowGroupMember(AllowGroup.WROTE, "shop", "Shop", 20L, MemberSource.HISTORY, true),
        )
        val content = BackupContent(
            rules = listOf(SpamRule(kind = ListKind.BLOCK, type = RuleType.NUMBER, pattern = "+15550009999")),
            forwardRules = emptyList(),
            settings = BackupSettings(
                spamMode = UnknownSenderMode.OFF, filterOn = false, codeGroupOn = false, wroteGroupOn = true,
            ),
            groupMembers = members,
        )
        val text = SettingsBackupFormat.encode(content, 1)
        assertThat(text).doesNotContain("subscriptionId")
        assertThat(text).contains("\"filterOn\": false")
        assertThat(text).contains("\"spamMode\": \"OFF\"")
        val back = SettingsBackupFormat.decode(text)
        assertThat(back.settings.filterOn).isFalse()
        assertThat(back.settings.codeGroupOn).isFalse()
        assertThat(back.settings.wroteGroupOn).isTrue()
        // The key is made again on the phone that restores: it follows that phone's SIM countries.
        assertThat(back.groupMembers.map { it.copy(senderKey = "") })
            .containsExactlyElementsIn(members.map { it.copy(senderKey = "") }).inOrder()
    }

    /** A-9: the Keep state of an automatic allow rule travels; an old file's automatic allow counts as kept. */
    @Test fun ruleAcknowledgedRoundTrips() {
        val auto = SpamRule(
            kind = ListKind.ALLOW, type = RuleType.NUMBER, pattern = "+15550001234",
            note = "@spamless:allowed_from_keyword", acknowledged = true,
        )
        val rules = listOf(auto, auto.copy(acknowledged = false, pattern = "+15550005678"))
        val text = SettingsBackupFormat.encode(BackupContent(rules, emptyList(), BackupSettings()), 1)
        val back = SettingsBackupFormat.decode(text).rules
        assertThat(back.map { it.acknowledged }).containsExactly(true, false).inOrder()

        val old = """{"app":"spamless","kind":"settings-backup","version":1,"rules":[
            {"kind":"ALLOW","type":"NUMBER","pattern":"+15550001234","note":"@spamless:allowed_from_keyword"},
            {"kind":"ALLOW","type":"NUMBER","pattern":"+15550005678","note":"My friend"}]}"""
        assertThat(SettingsBackupFormat.decode(old).rules.map { it.acknowledged })
            .containsExactly(true, false).inOrder()
    }

    /** A-9: a sender name or number longer than 40 characters is not dropped (up to 200). */
    @Test fun longMemberAddressesAreKept() {
        val long = "N" + "a".repeat(60)
        val tooLong = "N" + "a".repeat(BackupGroupMembers.MAX_ADDRESS)
        val text = SettingsBackupFormat.encode(
            BackupContent(
                emptyList(), emptyList(), BackupSettings(),
                groupMembers = listOf(
                    AllowGroupMember(AllowGroup.WROTE, "", long, 1L, MemberSource.LIVE, false),
                    AllowGroupMember(AllowGroup.WROTE, "", tooLong, 2L, MemberSource.LIVE, false),
                ),
            ),
            1,
        )
        assertThat(SettingsBackupFormat.decode(text).groupMembers.map { it.address }).containsExactly(long)
    }

    @Test fun badMembersAreSkipped() {
        val text = """{"app":"spamless","kind":"settings-backup","version":1,
            "groupMembers":[{"group":"FRIENDS","address":"+15550001234"},{"group":"CODE","address":""},
                            {"group":"CODE","address":"+15550001234"},"nonsense"]}"""
        val members = SettingsBackupFormat.decode(text).groupMembers
        assertThat(members.map { it.address }).containsExactly("+15550001234")
        assertThat(members.single().source).isEqualTo(MemberSource.HISTORY)
        assertThat(members.single().acknowledged).isTrue()
    }

    @Test fun theNewSettingsAreListedWhenTheyDiffer() {
        val changed = SettingsBackupFormat.changedSettings(
            BackupSettings(filterOn = true, codeGroupOn = true),
            BackupSettings(filterOn = false, codeGroupOn = true, wroteGroupOn = false),
        )
        assertThat(changed).containsExactly(BackupSettings.FILTER_ON, BackupSettings.WROTE_GROUP_ON)
    }
}

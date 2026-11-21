package com.glooboo.umessage.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.spam.AllowGroup
import com.glooboo.umessage.spam.data.RemovedMembers
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * B-9 hand-over to step F: the senders removed from a group rule (preferences `umessage_group_removed`) travel in the
 * settings backup, so a restore does not let the history check add them again. Older files without them still read.
 * Every number is made up.
 */
@RunWith(AndroidJUnit4::class)
class RemovedMembersBackupTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun prefs() = context.getSharedPreferences(RemovedMembers.PREFS, Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        RemovedMembers.reset()
        RemovedMembers.install(prefs().also { it.edit().clear().commit() })
    }

    @After
    fun tearDown() = RemovedMembers.reset()

    @Test
    fun theRemovedSendersAreReadFromRemovedMembers() {
        RemovedMembers.add(AllowGroup.CODE, listOf("+15550001234"))
        RemovedMembers.add(AllowGroup.WROTE, listOf("+15550001235", "shopname"))

        assertThat(RemovedMembers.entries()).hasSize(3)
        assertThat(RemovedMarks.read()).containsExactly(
            RemovedGroupMark(AllowGroup.CODE, "+15550001234"),
            RemovedGroupMark(AllowGroup.WROTE, "+15550001235"),
            RemovedGroupMark(AllowGroup.WROTE, "shopname"),
        )
    }

    @Test
    fun theMarksSurviveTheFileAndARestorePutsThemBack() {
        val marks = listOf(
            RemovedGroupMark(AllowGroup.CODE, "+15550001234"),
            RemovedGroupMark(AllowGroup.WROTE, "shopname"),
        )
        val text = SettingsBackupFormat.encode(
            BackupContent(emptyList(), emptyList(), BackupSettings(), removedMembers = marks), 1,
        )

        val back = SettingsBackupFormat.decode(text).removedMembers
        RemovedMarks.restore(back)

        assertThat(back).containsExactlyElementsIn(marks)
        assertThat(RemovedMembers.contains(AllowGroup.CODE, listOf("+15550001234"))).isTrue()
        assertThat(RemovedMembers.contains(AllowGroup.WROTE, listOf("shopname"))).isTrue()
        assertThat(RemovedMembers.contains(AllowGroup.CODE, listOf("shopname"))).isFalse()
    }

    @Test
    fun anOlderFileWithoutMarksAndUnusableMarksReadAsNone() {
        val old = SettingsBackupFormat.encode(BackupContent(emptyList(), emptyList(), BackupSettings()), 1)
        assertThat(SettingsBackupFormat.decode(old).removedMembers).isEmpty()
        assertThat(old).doesNotContain("removedMembers")

        val odd = old.trimEnd().removeSuffix("}") +
            ""","removedMembers":[{"group":"NOPE","key":"x"},{"group":"CODE","key":""},{"group":"CODE"},7,""" +
            """{"group":"CODE","key":"+15550001234"},{"group":"CODE","key":"+15550001234"}]}"""

        assertThat(SettingsBackupFormat.decode(odd).removedMembers)
            .containsExactly(RemovedGroupMark(AllowGroup.CODE, "+15550001234"))
    }
}

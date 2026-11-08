package com.glooboo.umessage.export

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.export.HiddenBackupPolicy.Decision
import com.glooboo.umessage.helpers.BackupSummaryText
import com.glooboo.umessage.models.ExportSummary
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** Q14: what a full backup does with the hidden chats, and how the summary tells what was left out. */
@RunWith(AndroidJUnit4::class)
class HiddenBackupPolicyTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun decide(
        full: Boolean = true, hidden: Int = 2, lockOn: Boolean = true, unlocked: Boolean = false, skip: Boolean = false,
    ) = HiddenBackupPolicy.decide(full, hidden, lockOn, unlocked, skip)

    @Test
    fun lockedHiddenChatsInAFullBackup_ask() {
        assertThat(decide()).isEqualTo(Decision.ASK)
    }

    @Test
    fun skipChosen_skips() {
        assertThat(decide(skip = true)).isEqualTo(Decision.SKIP)
    }

    @Test
    fun unlockSucceeded_includes_evenAfterSkipWasOffered() {
        assertThat(decide(unlocked = true)).isEqualTo(Decision.INCLUDE)
        assertThat(decide(unlocked = true, skip = true)).isEqualTo(Decision.INCLUDE)
    }

    @Test
    fun unlockFailedOrCancelled_asksAgain() {
        // a failed unlock leaves the session locked and nothing chosen: the user can retry or skip
        assertThat(decide(unlocked = false, skip = false)).isEqualTo(Decision.ASK)
    }

    @Test
    fun lockOff_includesWithoutPrompt() {
        assertThat(decide(lockOn = false)).isEqualTo(Decision.INCLUDE)
        assertThat(decide(lockOn = false, skip = true)).isEqualTo(Decision.INCLUDE)
    }

    @Test
    fun noHiddenChats_includesWithoutPrompt() {
        assertThat(decide(hidden = 0)).isEqualTo(Decision.INCLUDE)
        assertThat(decide(hidden = 0, unlocked = true)).isEqualTo(Decision.INCLUDE)
    }

    @Test
    fun chosenChats_neverPrompt() {
        assertThat(decide(full = false)).isEqualTo(Decision.INCLUDE)
        assertThat(decide(full = false, skip = true)).isEqualTo(Decision.INCLUDE)
    }

    @Test
    fun everyCombination_asksOnlyWhenFullHiddenLockOnAndLocked() {
        for (bits in 0 until 32) {
            val full = bits and 1 != 0
            val lockOn = bits and 2 != 0
            val unlocked = bits and 4 != 0
            val skip = bits and 8 != 0
            val hidden = (bits shr 4) and 1
            val expected = when {
                !full || hidden == 0 || !lockOn || unlocked -> Decision.INCLUDE
                skip -> Decision.SKIP
                else -> Decision.ASK
            }
            assertThat(decide(full, hidden, lockOn, unlocked, skip)).isEqualTo(expected)
        }
    }

    @Test
    fun withoutChosen_dropsTheHiddenIdsOnly() {
        assertThat(ExportScope.withoutChosen(listOf(1L, 2L, 3L), setOf(2L, 9L))).containsExactly(1L, 3L).inOrder()
        assertThat(ExportScope.withoutChosen(listOf(1L, 2L), emptySet())).containsExactly(1L, 2L).inOrder()
    }

    @Test
    fun summary_saysHowManyHiddenChatsWereLeftOut() {
        val summary = ExportSummary().apply {
            sms = 3
            hiddenLeftOut = 2
        }
        val text = BackupSummaryText.export(context, summary, null)
        assertThat(text).contains(context.getString(com.glooboo.umessage.R.string.backup_export_hidden_left_out, 2))
    }

    @Test
    fun summary_saysNothingWhenNoHiddenChatWasLeftOut() {
        val summary = ExportSummary().apply { sms = 3 }
        val text = BackupSummaryText.export(context, summary, null)
        val none = context.getString(com.glooboo.umessage.R.string.backup_export_hidden_left_out, 0)
        assertThat(text).doesNotContain(none)
    }
}

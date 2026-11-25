package com.glooboo.umessage.ui.thread

import androidx.compose.ui.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.R
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** UX review wave 8, thread screen: the selection menu, the received bubble colour and the copy chips. */
@RunWith(AndroidJUnit4::class)
class ThreadUx8bTest {

    private fun entries(single: Boolean = true, recycleBin: Boolean = false) = selectionEntries(
        hasText = true, single = single, canSave = false, readAloudOk = true,
        readAloudLabel = R.string.read_aloud_action, isRecycleBin = recycleBin,
    )

    @Test
    fun readAloudAndRemindFollowTheNoteRow() {
        val e = entries()
        assertThat(e.map { it.action }).containsExactly(
            SelectionAction.Share, SelectionAction.Details, SelectionAction.SaveText,
            SelectionAction.ReadAloud, SelectionAction.Remind,
        ).inOrder()
    }

    @Test
    fun recycleBinOffersRestoreAndNoReminder() {
        val e = entries(recycleBin = true)
        assertThat(e.map { it.action }).contains(SelectionAction.Restore)
        assertThat(e.map { it.action }).doesNotContain(SelectionAction.Remind)
    }

    @Test
    fun multipleSelectionKeepsOnlyTheRowsForSeveralMessages() {
        val e = selectionEntries(
            hasText = true, single = false, canSave = false, readAloudOk = false,
            readAloudLabel = R.string.read_aloud_action, isRecycleBin = false,
        )
        assertThat(e.map { it.action }).containsExactly(SelectionAction.Share).inOrder()
        assertThat(e.map { it.action }).doesNotContain(SelectionAction.Details, SelectionAction.Remind)
    }

    @Test
    fun darkReceivedBubbleIsLighterAndLightOneIsUnchanged() {
        val highest = Color(0xFF2B2930)
        val text = Color.White
        assertThat(receivedBubbleColor(highest, text, dark = false)).isEqualTo(highest)
        val lifted = receivedBubbleColor(highest, text, dark = true)
        assertThat(contrastRatio(lifted, Color.Black)).isGreaterThan(contrastRatio(highest, Color.Black))
    }
}

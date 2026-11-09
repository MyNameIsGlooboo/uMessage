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
    fun remindAndReadAloudFoldIntoMore() {
        val e = entries()
        assertThat(e.main.map { it.action }).containsExactly(
            SelectionAction.Share, SelectionAction.SelectText, SelectionAction.Details, SelectionAction.SaveText,
        ).inOrder()
        assertThat(e.more.map { it.action }).containsExactly(SelectionAction.ReadAloud, SelectionAction.Remind)
    }

    @Test
    fun recycleBinOffersRestoreAndNoReminder() {
        val e = entries(recycleBin = true)
        assertThat(e.main.map { it.action }).contains(SelectionAction.Restore)
        assertThat(e.more.map { it.action }).doesNotContain(SelectionAction.Remind)
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

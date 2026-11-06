package com.glooboo.umessage.ui.thread

import android.provider.Telephony
import androidx.compose.ui.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.R
import com.glooboo.umessage.models.Message
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** UX review wave 8, thread screen: the selection menu, the received bubble colour and the one copy chip. */
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

    private fun message(id: Long, body: String, received: Boolean) = Message(
        id = id, body = body,
        type = if (received) Telephony.Sms.MESSAGE_TYPE_INBOX else Telephony.Sms.MESSAGE_TYPE_SENT,
        status = 0, participants = arrayListOf(), date = id.toInt(), read = true, threadId = 1L, isMMS = false,
        attachment = null, senderPhoneNumber = "+15550100", senderName = "", senderPhotoUri = "", subscriptionId = -1,
    )

    private fun bubble(m: Message) = ThreadRow.Bubble(m, BubbleGroup.Single, showSender = false, key = "m${m.id}")

    @Test
    fun onlyTheNewestCodeGetsTheCopyChip() {
        val oldCode = message(1, "Your code: 482913", received = true)
        val chat = message(2, "hello there", received = true)
        val newCode = message(3, "Your code: 771204", received = true)
        val rows = listOf(newCode, chat, oldCode).map(::bubble)
        assertThat(newestCopyChipId(rows)).isEqualTo(newCode.getStableId())
        assertThat(newestCopyChipId(rows.drop(1))).isEqualTo(oldCode.getStableId())
        assertThat(newestCopyChipId(listOf(bubble(chat)))).isNull()
    }
}

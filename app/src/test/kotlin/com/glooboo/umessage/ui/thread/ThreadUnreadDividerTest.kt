package com.glooboo.umessage.ui.thread

import android.provider.Telephony
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.models.ThreadItem
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ThreadUnreadDividerTest {

    private fun message(id: Long, date: Int, received: Boolean = true, read: Boolean = true) = Message(
        id = id, body = "m$id",
        type = if (received) Telephony.Sms.MESSAGE_TYPE_INBOX else Telephony.Sms.MESSAGE_TYPE_SENT,
        status = 0, participants = ArrayList(), date = date, read = read, threadId = 1L, isMMS = false,
        attachment = null, senderPhoneNumber = "+1", senderName = "+1", senderPhotoUri = "", subscriptionId = 1,
    )

    private fun items(messages: List<Message>, anchor: Long?) =
        buildThreadItems(messages, emptyMap(), seen = false, firstUnreadId = anchor).items

    @Test
    fun theLineSitsBelowTheDateHeaderAndAboveTheFirstUnreadMessage() {
        val messages = listOf(message(1, 1_000), message(2, 1_010, read = false), message(3, 1_020, read = false))
        val anchor = firstUnreadMessageId(messages)
        assertThat(anchor).isEqualTo(messages[1].getStableId())
        assertThat(items(messages, anchor)).containsExactly(
            ThreadItem.ThreadDateTime(1_000, "?"),
            messages[0],
            ThreadItem.ThreadUnreadDivider(messages[1].getStableId()),
            messages[1],
            messages[2],
        ).inOrder()
    }

    @Test
    fun aDateHeaderStaysAboveTheLine() {
        val messages = listOf(message(1, 1_000), message(2, 5_000, read = false))
        val result = items(messages, firstUnreadMessageId(messages))
        assertThat(result.map { it::class }).containsExactly(
            ThreadItem.ThreadDateTime::class, Message::class,
            ThreadItem.ThreadDateTime::class, ThreadItem.ThreadUnreadDivider::class, Message::class,
        ).inOrder()
    }

    @Test
    fun noLineWhenEverythingIsRead() {
        val messages = listOf(message(1, 1_000), message(2, 1_010))
        assertThat(firstUnreadMessageId(messages)).isNull()
        assertThat(items(messages, null).filterIsInstance<ThreadItem.ThreadUnreadDivider>()).isEmpty()
    }

    @Test
    fun sentMessagesNeverStartTheLine() {
        val messages = listOf(message(1, 1_000, received = false, read = false))
        assertThat(firstUnreadMessageId(messages)).isNull()
    }

    @Test
    fun theLineStaysAfterTheMessagesAreMarkedRead() {
        val messages = listOf(message(1, 1_000), message(2, 1_010, read = false))
        val anchor = firstUnreadMessageId(messages)
        val markedRead = messages.map { it.copy(read = true) }
        assertThat(items(markedRead, anchor).filterIsInstance<ThreadItem.ThreadUnreadDivider>()).hasSize(1)
    }

    @Test
    fun noLineOnceTheAnchorIsCleared() {
        val messages = listOf(message(1, 1_000), message(2, 1_010, read = false))
        assertThat(items(messages, null).filterIsInstance<ThreadItem.ThreadUnreadDivider>()).isEmpty()
    }

    @Test
    fun theLineBreaksTheRunOfGroupedBubbles() {
        val messages = listOf(message(1, 1_000), message(2, 1_010, read = false))
        val groups = buildThreadRows(items(messages, firstUnreadMessageId(messages)), isGroup = false)
        assertThat(groups.filterIsInstance<ThreadRow.UnreadDivider>()).hasSize(1)
        assertThat(groups.filterIsInstance<ThreadRow.Bubble>().map { it.group })
            .containsExactly(BubbleGroup.Single, BubbleGroup.Single)
    }
}

package com.glooboo.umessage.ui.thread

import android.provider.Telephony
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.models.ThreadItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadRowsTest {

    private fun message(id: Long, received: Boolean, sender: String = "+1", scheduled: Boolean = false) = Message(
        id = id,
        body = "m$id",
        type = if (received) Telephony.Sms.MESSAGE_TYPE_INBOX else Telephony.Sms.MESSAGE_TYPE_SENT,
        status = 0,
        participants = ArrayList(),
        date = id.toInt(),
        read = true,
        threadId = 1,
        isMMS = false,
        attachment = null,
        senderPhoneNumber = sender,
        senderName = sender,
        senderPhotoUri = "",
        subscriptionId = 1,
        isScheduled = scheduled,
    )

    @Test
    fun theHeaderAboveAScheduledMessageSaysSoAndNamesTheDay() {
        val items = listOf(
            ThreadItem.ThreadDateTime(10, "1"), message(10, false),
            ThreadItem.ThreadDateTime(20, "1"), message(20, false, scheduled = true),
        )
        val headers = buildThreadRows(items, false).filterIsInstance<ThreadRow.DateHeader>()
        assertFalse(headers[0].scheduled)
        assertTrue(headers[1].scheduled)
        assertTrue(headers[1].showDay)
    }

    private fun groups(items: List<ThreadItem>, isGroup: Boolean = false) =
        buildThreadRows(items, isGroup).filterIsInstance<ThreadRow.Bubble>().map { it.group }

    @Test
    fun consecutiveMessagesFromOneSideFormARun() {
        val items = listOf(message(1, false), message(2, false), message(3, false))
        assertEquals(listOf(BubbleGroup.First, BubbleGroup.Middle, BubbleGroup.Last), groups(items))
    }

    @Test
    fun alternatingSidesAreSingles() {
        val items = listOf(message(1, true), message(2, false), message(3, true))
        assertEquals(listOf(BubbleGroup.Single, BubbleGroup.Single, BubbleGroup.Single), groups(items))
    }

    @Test
    fun dateHeaderBreaksTheRun() {
        val items = listOf(message(1, false), ThreadItem.ThreadDateTime(500, "1"), message(2, false))
        assertEquals(listOf(BubbleGroup.Single, BubbleGroup.Single), groups(items))
    }

    @Test
    fun scheduledMessagesAreNotGroupedWithSentOnes() {
        val items = listOf(message(1, false), message(2, false, scheduled = true))
        assertEquals(listOf(BubbleGroup.Single, BubbleGroup.Single), groups(items))
    }

    @Test
    fun groupChatsLabelTheFirstMessageOfEachReceivedRun() {
        val items = listOf(message(1, true, "+1"), message(2, true, "+1"), message(3, true, "+2"), message(4, false))
        val bubbles = buildThreadRows(items, isGroup = true).filterIsInstance<ThreadRow.Bubble>()
        assertEquals(listOf(true, false, true, false), bubbles.map { it.showSender })
        assertEquals(listOf(BubbleGroup.First, BubbleGroup.Last, BubbleGroup.Single, BubbleGroup.Single), bubbles.map { it.group })
    }

    @Test
    fun oneToOneChatsNeverLabelSenders() {
        val items = listOf(message(1, true), message(2, true))
        assertTrue(buildThreadRows(items, isGroup = false).filterIsInstance<ThreadRow.Bubble>().none { it.showSender })
    }

    @Test
    fun statusRowsFollowTheirMessageAndKeepKeysUnique() {
        val items = listOf(
            message(1, false),
            ThreadItem.ThreadError(1, "m1"),
            message(2, false),
            ThreadItem.ThreadSent(2, delivered = true),
        )
        val rows = buildThreadRows(items, isGroup = false)
        assertEquals(4, rows.size)
        assertTrue(rows[1] is ThreadRow.Failed)
        assertTrue((rows[3] as ThreadRow.Sent).delivered)
        assertEquals(rows.size, rows.map { it.key }.toSet().size)
        assertFalse(rows.any { it is ThreadRow.DateHeader })
    }

    @Test
    fun duplicateDateHeadersGetDistinctKeys() {
        val items = listOf(ThreadItem.ThreadDateTime(10, "1"), ThreadItem.ThreadDateTime(10, "1"))
        val rows = buildThreadRows(items, isGroup = false)
        assertEquals(2, rows.map { it.key }.toSet().size)
    }
}

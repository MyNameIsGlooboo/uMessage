package com.glooboo.umessage.ui.details

import android.provider.Telephony
import com.glooboo.umessage.models.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** CL-23: "Search in conversation" from Details. */
class ConversationSearchTest {

    private fun message(id: Long, body: String, date: Int, scheduled: Boolean = false) = Message(
        id = id, body = body, type = Telephony.Sms.MESSAGE_TYPE_INBOX, status = 0, participants = ArrayList(),
        date = date, read = true, threadId = 1, isMMS = false, attachment = null,
        senderPhoneNumber = "+1", senderName = "", senderPhotoUri = "", subscriptionId = 1, isScheduled = scheduled,
    )

    private val messages = listOf(
        message(1, "Your code is 482913", date = 10),
        message(2, "رمز پویا: ۵۲۸۱۴۷۳۶", date = 30),
        message(3, "See you at the CODE review", date = 20),
        message(4, "code later", date = 40, scheduled = true),
    )

    @Test
    fun matchesIgnoreCaseNewestFirstWithoutScheduledDrafts() {
        assertEquals(listOf(3L, 1L), searchConversation(messages, "code").map { it.id })
    }

    @Test
    fun digitsMatchInEitherScript() {
        assertEquals(listOf(2L), searchConversation(messages, "5281").map { it.id })
        assertEquals(listOf(1L), searchConversation(messages, "۴۸۲۹").map { it.id })
    }

    @Test
    fun aOneLetterQueryFindsNothing() {
        assertTrue(searchConversation(messages, "c").isEmpty())
    }
}

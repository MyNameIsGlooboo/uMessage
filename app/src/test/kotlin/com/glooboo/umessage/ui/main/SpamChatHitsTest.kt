package com.glooboo.umessage.ui.main

import android.provider.Telephony
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.models.Message
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Provider truth (U9): the Spam section of search has one row per Spam chat, newest first. Fake numbers only. */
class SpamChatHitsTest {

    private fun chat(thread: Long, title: String, date: Int) = Conversation(
        threadId = thread, snippet = "", date = date, read = true, title = title, photoUri = "",
        isGroupConversation = false, phoneNumber = "+1555000123$thread",
    )

    private fun message(id: Long, thread: Long, body: String, date: Int) = Message(
        id = id, body = body, type = Telephony.Sms.MESSAGE_TYPE_INBOX, status = 0, participants = ArrayList(),
        date = date, read = true, threadId = thread, isMMS = false, attachment = null,
        senderPhoneNumber = "+1555000123$thread", senderName = "", senderPhotoUri = "", subscriptionId = 0,
    )

    private val chats = mapOf(1L to chat(1, "Prize club", 300), 2L to chat(2, "", 100))

    private fun build(found: List<Conversation>, messages: List<Message>) = buildSpamChatHits(
        chats = found,
        messages = messages,
        chatOf = chats,
        query = "prize",
        title = { "T:$it" },
        formatDate = { "D$it" },
    )

    @Test
    fun oneRowPerChatNewestFirstWithTheCountOfMatches() {
        val hits = build(
            found = listOf(chats.getValue(1)),
            messages = listOf(
                message(10, 2, "Your prize waits", date = 400),
                message(11, 2, "Prize again", date = 500),
                // Not a Spam chat: never listed here.
                message(12, 9, "prize elsewhere", date = 900),
            ),
        )

        assertThat(hits.map { it.open.threadId }).containsExactly(2L, 1L).inOrder()
        val byMessage = hits.first()
        assertThat(byMessage.row.count).isEqualTo(2)
        assertThat(byMessage.row.snippet).isEqualTo("Prize again")
        assertThat(byMessage.row.date).isEqualTo("D500")
        assertThat(byMessage.row.title).isEqualTo("T:+15550001232")
        assertThat(byMessage.open.messageId).isEqualTo(11L)
    }

    @Test
    fun aChatFoundByItsTitleOnlyShowsNoTextAndOpensTheChat() {
        val hit = build(found = listOf(chats.getValue(1)), messages = emptyList()).single()

        assertThat(hit.row.snippet).isEmpty()
        assertThat(hit.row.count).isEqualTo(1)
        assertThat(hit.row.title).isEqualTo("T:Prize club")
        assertThat(hit.open.messageId).isEqualTo(CONVERSATION_HIT)
        assertThat(hit.open.threadId).isEqualTo(1L)
    }

    @Test
    fun eachRowIsKeyedByItsThreadSoTwoChatsOfOneNumberStayTwoRows() {
        val twin = chat(3, "Prize club", 200).copy(phoneNumber = chats.getValue(1).phoneNumber)
        val hits = buildSpamChatHits(
            chats = listOf(chats.getValue(1), twin),
            messages = emptyList(),
            chatOf = chats + (3L to twin),
            query = "prize",
            title = { it },
            formatDate = { "$it" },
        )

        assertThat(hits.map { it.row.address }).containsExactly("1", "3").inOrder()
    }
}

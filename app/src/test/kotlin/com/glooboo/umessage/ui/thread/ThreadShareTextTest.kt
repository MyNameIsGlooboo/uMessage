package com.glooboo.umessage.ui.thread

import android.provider.Telephony
import com.glooboo.umessage.models.Message
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ThreadShareTextTest {

    private val formatter = ConversationTextFormatter(me = "Me", attachmentLabel = "[Attachment]") { "D${it.date}" }

    private fun message(
        id: Long,
        body: String,
        received: Boolean = true,
        name: String = "Maya",
        date: Int = id.toInt(),
    ) = Message(
        id = id, body = body,
        type = if (received) Telephony.Sms.MESSAGE_TYPE_INBOX else Telephony.Sms.MESSAGE_TYPE_SENT,
        status = 0, participants = ArrayList(), date = date, read = true, threadId = 1L, isMMS = false,
        attachment = null, senderPhoneNumber = "+15550100", senderName = name, senderPhotoUri = "", subscriptionId = 1,
    )

    private fun String.plain() = replace("\u2068", "").replace("\u2069", "")

    @Test
    fun mixedSentAndReceivedMessagesComeOldestFirstWithDateAndSender() {
        val shared = formatter.format(listOf(message(2, "Fine, you?", received = false), message(1, "Hi")))
        assertThat(shared.text.plain()).isEqualTo("[D1] Maya: Hi\n\n[D2] Me: Fine, you?")
        assertThat(shared.count).isEqualTo(2)
        assertThat(shared.first?.id).isEqualTo(1L)
        assertThat(shared.last?.id).isEqualTo(2L)
    }

    @Test
    fun aTitleGoesOnTop() {
        val shared = formatter.format(listOf(message(1, "Hi")), title = "Maya")
        assertThat(shared.text.plain()).isEqualTo("Maya\n\n[D1] Maya: Hi")
    }

    @Test
    fun aPictureOnlyMessageSaysAttachment() {
        val attachment = com.glooboo.umessage.models.MessageAttachment(
            id = 1, text = "",
            attachments = arrayListOf(
                com.glooboo.umessage.models.Attachment(null, 1, "content://x", "image/jpeg", 1, 1, "x.jpg"),
            ),
        )
        val mms = message(1, "").copy(isMMS = true, attachment = attachment)
        assertThat(formatter.format(listOf(mms)).text.plain()).isEqualTo("[D1] Maya: [Attachment]")
        val withText = message(2, "Look").copy(isMMS = true, attachment = attachment)
        assertThat(formatter.format(listOf(withText)).text.plain()).isEqualTo("[D2] Maya: Look\n[Attachment]")
    }

    @Test
    fun emptyMessagesAreLeftOut() {
        val shared = formatter.format(listOf(message(1, ""), message(2, "Hi")))
        assertThat(shared.count).isEqualTo(1)
    }

    @Test
    fun everyPartIsIsolatedSoPersianAndEnglishDoNotMix() {
        val persian = message(1, "\u0633\u0644\u0627\u0645 hello", name = "\u0645\u0627\u06CC\u0627")
        val shared = formatter.format(listOf(persian))
        assertThat(shared.text).isEqualTo(
            "[\u2068D1\u2069] \u2068\u0645\u0627\u06CC\u0627\u2069: \u2068\u0633\u0644\u0627\u0645 hello\u2069"
        )
    }

    @Test
    fun aSenderWithoutANameShowsTheNumber() {
        val shared = formatter.format(listOf(message(1, "Hi", name = "")))
        assertThat(shared.text.plain()).isEqualTo("[D1] +15550100: Hi")
    }

    @Test
    fun noMessagesGiveAnEmptyResult() {
        val shared = formatter.format(emptyList())
        assertThat(shared.count).isEqualTo(0)
        assertThat(shared.first).isNull()
    }

    @Test
    fun theSizeLimitMatchesWhatAnIntentCanCarry() {
        val long = formatter.format(List(2_000) { message(it + 1L, "x".repeat(200)) })
        assertThat(long.text.toByteArray().size).isGreaterThan(SHARE_TEXT_LIMIT_BYTES)
    }
}

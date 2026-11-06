package com.glooboo.umessage.export

import android.provider.Telephony.Sms
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.models.Attachment
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.models.MessageAttachment
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** The rows, statuses, date filter and hash of a conversation export, with invented messages. */
@RunWith(AndroidJUnit4::class)
class ConversationExportTest {
    private val labels = ExportLabels(
        me = "Me", attachmentTag = "[Attachment]",
        status = ExportStatusLabels("Received", "Sent", "Delivered", "Failed", "Sending", "Draft"),
        unnamedAttachment = "unnamed",
        attachmentLine = { name, type -> "Attachment: $name ($type)" },
    )
    private val builder = ConversationExportBuilder(labels, { "T${it.date}" }, { a, b -> "${a.date}..${b.date}" })
    private val numbers = listOf("+15550001")

    private fun message(
        id: Long, date: Int, body: String, type: Int = Sms.MESSAGE_TYPE_INBOX, status: Int = Sms.STATUS_NONE,
        mms: Boolean = false, attachments: List<Attachment> = emptyList(), name: String = "Sam",
    ) = Message(
        id = id, body = body, type = type, status = status, participants = arrayListOf(), date = date, read = true,
        threadId = 1, isMMS = mms,
        attachment = attachments.takeIf { it.isNotEmpty() }?.let { MessageAttachment(id, "", ArrayList(it)) },
        senderPhoneNumber = "+15550001", senderName = name, senderPhotoUri = "", subscriptionId = -1,
    )

    private fun file(name: String, type: String) = Attachment(null, 1, "content://x/$name", type, 0, 0, name)

    private val sample = listOf(
        message(2, 200, "sent one", Sms.MESSAGE_TYPE_SENT, Sms.STATUS_COMPLETE),
        message(1, 100, "hello"),
        message(3, 300, "سلام 👋", Sms.MESSAGE_TYPE_SENT, Sms.STATUS_NONE),
    )

    @Test
    fun rows_areOldestFirstWithDirectionPeopleAndStatus() {
        val export = builder.build(sample, "Sam", numbers)
        assertThat(export.rows.map { it.body }).containsExactly("hello", "sent one", "سلام 👋").inOrder()
        val received = export.rows[0]
        assertThat(received.direction).isEqualTo(ExportDirection.RECEIVED)
        assertThat(received.from).isEqualTo("Sam (+15550001)")
        assertThat(received.to).isEqualTo("Me")
        assertThat(received.status).isEqualTo("Received")
        val sent = export.rows[1]
        assertThat(sent.direction).isEqualTo(ExportDirection.SENT)
        assertThat(sent.from).isEqualTo("Me")
        assertThat(sent.to).isEqualTo("+15550001")
        assertThat(sent.status).isEqualTo("Delivered")
        assertThat(export.rows[2].status).isEqualTo("Sent")
        assertThat(export.rangeText).isEqualTo("100..300")
        assertThat(export.count).isEqualTo(3)
    }

    @Test
    fun aSenderWithoutAName_isShownAsTheNumberOnly() {
        val export = builder.build(listOf(message(1, 1, "x", name = "")), "t", numbers)
        assertThat(export.rows.single().from).isEqualTo("+15550001")
    }

    @Test
    fun statusMapping_coversSentFailedSendingDraftAndPictureMessages() {
        fun status(type: Int, status: Int = Sms.STATUS_NONE, mms: Boolean = false) =
            builder.statusOf(message(1, 1, "x", type, status, mms))
        assertThat(status(Sms.MESSAGE_TYPE_SENT)).isEqualTo("Sent")
        assertThat(status(Sms.MESSAGE_TYPE_SENT, Sms.STATUS_COMPLETE)).isEqualTo("Delivered")
        assertThat(status(Sms.MESSAGE_TYPE_SENT, Sms.STATUS_FAILED)).isEqualTo("Failed")
        assertThat(status(Sms.MESSAGE_TYPE_FAILED)).isEqualTo("Failed")
        assertThat(status(Sms.MESSAGE_TYPE_OUTBOX)).isEqualTo("Sending")
        assertThat(status(Sms.MESSAGE_TYPE_QUEUED)).isEqualTo("Sending")
        assertThat(status(Sms.MESSAGE_TYPE_DRAFT)).isEqualTo("Draft")
        assertThat(status(Sms.MESSAGE_TYPE_SENT, Sms.STATUS_COMPLETE, mms = true)).isEqualTo("Sent")
        assertThat(status(Sms.MESSAGE_TYPE_INBOX)).isEqualTo("Received")
    }

    @Test
    fun attachments_areListedByNameAndType_andAMessageWithOnlyAFileIsKept() {
        val files = listOf(file("a.jpg", "image/jpeg"), file("", "audio/amr"))
        val pic = message(1, 1, "", mms = true, attachments = files)
        val export = builder.build(listOf(pic, message(2, 2, "")), "t", numbers)
        assertThat(export.count).isEqualTo(1)
        assertThat(export.rows.single().attachments)
            .containsExactly("Attachment: a.jpg (image/jpeg)", "Attachment: unnamed (audio/amr)").inOrder()
        assertThat(export.textBlock).contains("[Attachment]")
    }

    @Test
    fun hash_isTheSha256OfTheTextBlock_andStable() {
        val export = builder.build(sample, "Sam", numbers)
        val expected = MessageDigest.getInstance("SHA-256").digest(export.textBlock.toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertThat(export.sha256).isEqualTo(expected)
        assertThat(export.sha256).hasLength(64)
        assertThat(builder.build(sample.reversed(), "Other title", listOf("+1")).sha256).isEqualTo(export.sha256)
        assertThat(builder.build(sample.drop(1), "Sam", numbers).sha256).isNotEqualTo(export.sha256)
    }

    @Test
    fun textFile_holdsTheHashedBlockExactly_soTheHashCanBeChecked() {
        val export = builder.build(sample, "Sam", numbers)
        val text = ConversationTextWriter.render(export, listOf("Title", "Line"), listOf("SHA-256: ${export.sha256}"))
        assertThat(text).contains(export.textBlock)
        assertThat(text).startsWith("Title\nLine\n\n")
        assertThat(text).endsWith("SHA-256: ${export.sha256}")
        val block = text.removePrefix("Title\nLine\n\n").removeSuffix("\n\nSHA-256: ${export.sha256}")
        assertThat(ConversationExportBuilder.sha256(block)).isEqualTo(export.sha256)
    }

    @Test
    fun noMessages_giveAnEmptyExportWithoutARange() {
        val export = builder.build(emptyList(), "Sam", numbers)
        assertThat(export.count).isEqualTo(0)
        assertThat(export.rangeText).isNull()
    }

    @Test
    fun hugeAndPersianMessages_buildWithoutTrouble() {
        val huge = message(1, 1, "x".repeat(500_000))
        val export = builder.build(listOf(huge, message(2, 2, "گفتگوی طولانی ".repeat(1000))), "t", numbers)
        assertThat(export.count).isEqualTo(2)
        assertThat(export.sha256).hasLength(64)
    }

    // ---- date ranges ------------------------------------------------------------------------------------------

    private val zone: ZoneId = ZoneOffset.UTC
    private fun at(day: String, hour: Int = 12) =
        LocalDate.parse(day).atTime(hour, 0).toInstant(ZoneOffset.UTC).epochSecond.toInt()

    private val dated = listOf(
        message(1, at("2026-10-01"), "a"), message(2, at("2026-10-20"), "b"),
        message(3, at("2026-10-31", 23), "c"), message(4, at("2026-11-02"), "d"),
    )
    private val now = Instant.parse("2026-11-04T12:00:00Z").toEpochMilli()

    @Test
    fun all_keepsEverything() {
        assertThat(ExportRange.All.filter(dated, now, zone)).hasSize(4)
    }

    @Test
    fun lastDays_keepsOnlyTheRecentOnes() {
        val ids = ExportRange.LastDays(30).filter(dated, now, zone).map { it.id }
        assertThat(ids).containsExactly(2L, 3L, 4L)
        assertThat(ExportRange.LastDays(3).filter(dated, now, zone).map { it.id }).containsExactly(4L)
    }

    @Test
    fun between_includesBothWholeDays_andPutsASwappedPairRight() {
        val range = ExportRange.Between(LocalDate.parse("2026-10-20"), LocalDate.parse("2026-10-31"))
        assertThat(range.filter(dated, now, zone).map { it.id }).containsExactly(2L, 3L)
        val swapped = ExportRange.Between(LocalDate.parse("2026-10-31"), LocalDate.parse("2026-10-20"))
        assertThat(swapped.filter(dated, now, zone).map { it.id }).containsExactly(2L, 3L)
    }
}

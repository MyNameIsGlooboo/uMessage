package com.glooboo.umessage.helpers

import android.content.Context
import android.provider.Telephony.Mms
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.models.ImportSummary
import com.glooboo.umessage.models.MmsAddress
import com.glooboo.umessage.models.MmsBackup
import com.glooboo.umessage.models.MmsPart
import com.glooboo.umessage.models.SmsBackup
import com.glooboo.umessage.models.WriteOutcome
import com.glooboo.umessage.testing.FakeMessageStore
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.google.android.mms.pdu_alt.PduHeaders
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowLog
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.SequenceInputStream
import java.util.Base64
import java.util.Collections

/**
 * Backup and restore against a fake telephony store: idempotent re-import, group MMS, duplicate detection, honest
 * summaries, damaged files, streaming, and a full export / import round trip.
 */
@RunWith(AndroidJUnit4::class)
class MessagesBackupTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val json = Json { encodeDefaults = true }
    private val photo = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)

    @After
    fun tearDown() = resetSharedMessagesDatabase()

    private fun writer(own: List<String> = emptyList()) = MessagesWriter(context) { own }
    private fun importer(own: List<String> = emptyList()) = MessagesImporter(context, writer(own))
    private val all = MessagesImporter.Options(sms = true, mms = true)

    private fun sms(address: String = "+15550001", body: String? = "hi", date: Long = 1_000, type: Int = 1) =
        SmsBackup(-1, address, body, date, date, 0, null, 1, -1, type, null)

    private fun mms(
        date: Long = 1_000,
        box: Int = Mms.MESSAGE_BOX_INBOX,
        text: String? = "look",
        image: ByteArray? = photo,
        addresses: List<MmsAddress> = listOf(
            MmsAddress("+15550001", PduHeaders.FROM, 106),
            MmsAddress("+15550002", PduHeaders.TO, 106),
            MmsAddress("+15550003", PduHeaders.TO, 106),
        ),
        imageData: String? = image?.let { Base64.getEncoder().encodeToString(it) },
        transactionId: String? = null,
    ) = MmsBackup(
        creator = null, contentType = "application/vnd.wap.multipart.related", deliveryReport = 0, date = date,
        dateSent = 0, locked = 0, messageType = 132, messageBox = box, read = 1, readReport = 0, seen = 1,
        textOnly = if (image == null) 1 else 0, status = null, subject = null, subjectCharSet = null,
        subscriptionId = -1, transactionId = transactionId, addresses = addresses,
        parts = listOfNotNull(
            text?.let { MmsPart(null, "106", null, null, "text/plain", null, null, null, null, 0, it, null) },
            if (image != null || imageData != null) {
                MmsPart(null, null, null, "photo.jpg", "image/jpeg", null, null, null, "photo.jpg", 0, null, imageData)
            } else {
                null
            },
        ),
    )

    private fun file(vararg entries: Any): InputStream = entries.joinToString(",", "[", "]") { entry ->
        when (entry) {
            is SmsBackup -> json.encodeToString(SmsBackup.serializer(), entry)
            is MmsBackup -> json.encodeToString(MmsBackup.serializer(), entry)
            else -> entry.toString()
        }
    }.byteInputStream()

    // ---- MMS: idempotent, group, attachments (T10, DL-26, DL-27, DL-28) -----------------------------------------

    @Test
    fun reimportingAnMms_addsNoSecondCopyOfItsParts() {
        val store = FakeMessageStore.install()
        val w = writer()
        assertThat(w.writeMmsMessage(mms()).outcome).isEqualTo(WriteOutcome.ADDED)
        assertThat(writer().writeMmsMessage(mms()).outcome).isEqualTo(WriteOutcome.DUPLICATE)
        assertThat(store.count("pdu")).isEqualTo(1)
        assertThat(store.count("part")).isEqualTo(2)
        assertThat(store.count("addr")).isEqualTo(3)
    }

    @Test
    fun groupMms_isRestoredIntoTheGroupConversation() {
        val store = FakeMessageStore.install()
        writer().writeMmsMessage(mms())
        val thread = store.rows("pdu").single()["thread_id"] as Long
        assertThat(store.threadRecipients(thread)).containsExactly("+15550001", "+15550002", "+15550003")
    }

    @Test
    fun groupMms_leavesThisPhonesOwnNumberOut() {
        val store = FakeMessageStore.install()
        writer(own = listOf("+15550002")).writeMmsMessage(mms())
        val thread = store.rows("pdu").single()["thread_id"] as Long
        assertThat(store.threadRecipients(thread)).containsExactly("+15550001", "+15550003")
    }

    @Test
    fun groupMmsOnThePhone_isNotRestoredAgain_whenThePhoneDoesNotTellItsOwnNumber() {
        // Run 12: the phone stored the group picture without its own number (+15550002); the restoring phone does
        // not know that number, so the backup's recipients name a conversation of three that does not exist.
        val store = FakeMessageStore.install()
        val group = store.threadIdFor("+15550001", "+15550003")
        store.addMms(
            group, 1_000, Mms.MESSAGE_BOX_INBOX, "look", photo,
            listOf("+15550001" to PduHeaders.FROM, "+15550002" to PduHeaders.TO, "+15550003" to PduHeaders.TO),
        )
        val threads = store.count("threads")

        val first = writer(own = emptyList()).writeMmsMessage(mms())
        val second = writer(own = emptyList()).writeMmsMessage(mms())

        assertThat(first.outcome).isEqualTo(WriteOutcome.DUPLICATE)
        assertThat(second.outcome).isEqualTo(WriteOutcome.DUPLICATE)
        assertThat(store.count("pdu")).isEqualTo(1)
        assertThat(store.count("part")).isEqualTo(2)
        assertThat(store.rows("pdu").single()["thread_id"]).isEqualTo(group)
        // No empty conversation of three, dated at restore time, is made for it either.
        assertThat(store.count("threads")).isEqualTo(threads)
    }

    @Test
    fun groupMmsRestoredWithItsOwnNumberKnown_isNotRestoredAgainWhenItIsNot() {
        val store = FakeMessageStore.install()
        assertThat(writer(own = listOf("+15550002")).writeMmsMessage(mms()).outcome).isEqualTo(WriteOutcome.ADDED)

        val unknown = writer(own = emptyList()).writeMmsMessage(mms())
        val another = writer(own = listOf("+15550001234")).writeMmsMessage(mms())

        assertThat(unknown.outcome).isEqualTo(WriteOutcome.DUPLICATE)
        assertThat(another.outcome).isEqualTo(WriteOutcome.DUPLICATE)
        assertThat(store.count("pdu")).isEqualTo(1)
    }

    @Test
    fun theSameTextAtTheSameTimeFromAnotherSender_isStillAdded() {
        val store = FakeMessageStore.install()
        writer().writeMmsMessage(mms())
        val other = listOf(
            MmsAddress("+15550001234", PduHeaders.FROM, 106),
            MmsAddress("+15550002", PduHeaders.TO, 106),
            MmsAddress("+15550003", PduHeaders.TO, 106),
        )

        assertThat(writer().writeMmsMessage(mms(addresses = other)).outcome).isEqualTo(WriteOutcome.ADDED)
        assertThat(writer().writeMmsMessage(mms(addresses = other)).outcome).isEqualTo(WriteOutcome.DUPLICATE)
        assertThat(store.count("pdu")).isEqualTo(2)
    }

    private val sentToGroup = listOf(
        MmsAddress("insert-address-token", PduHeaders.FROM, 106),
        MmsAddress("+15550002", PduHeaders.TO, 106),
        MmsAddress("+15550003", PduHeaders.TO, 106),
        MmsAddress("+15550001234", PduHeaders.TO, 106),
    )

    private fun sentTo(number: String) =
        listOf(MmsAddress("insert-address-token", PduHeaders.FROM, 106), MmsAddress(number, PduHeaders.TO, 106))

    private val groupPairs =
        listOf("+15550001" to PduHeaders.FROM, "+15550002" to PduHeaders.TO, "+15550003" to PduHeaders.TO)

    @Test
    fun sentGroupMmsOnThePhone_isNotRestoredAgain_inAnotherConversation() {
        // The phone that sent it kept its own number (+15550001234) in the conversation; this one knows that number
        // and leaves it out, so the backup names another conversation.
        val store = FakeMessageStore.install()
        val kept = store.threadIdFor("+15550002", "+15550003", "+15550001234")
        store.addMms(kept, 1_000, Mms.MESSAGE_BOX_SENT, "look", photo, sentToGroup.map { it.address to it.type })
        val sent = mms(box = Mms.MESSAGE_BOX_SENT, addresses = sentToGroup)

        val result = writer(own = listOf("+15550001234")).writeMmsMessage(sent)

        assertThat(result.outcome).isEqualTo(WriteOutcome.DUPLICATE)
        assertThat(store.count("pdu")).isEqualTo(1)
    }

    @Test
    fun theSamePictureSentAsSeparateMessages_keepsEachOne() {
        val store = FakeMessageStore.install()

        val first = writer().writeMmsMessage(mms(box = Mms.MESSAGE_BOX_SENT, addresses = sentTo("+15550002")))
        val second = writer().writeMmsMessage(mms(box = Mms.MESSAGE_BOX_SENT, addresses = sentTo("+15550003")))

        assertThat(first.outcome).isEqualTo(WriteOutcome.ADDED)
        assertThat(second.outcome).isEqualTo(WriteOutcome.ADDED)
        assertThat(store.count("pdu")).isEqualTo(2)
    }

    @Test
    fun twoMessagesWithTheSameTimeAndTextFromOneSender_areBothKeptAndEachRestoredOnce() {
        // A picture with the same caption sent in the same second to a group and to one of its members.
        val store = FakeMessageStore.install()
        val toGroup = mms()
        val toOne = mms(
            addresses = listOf(
                MmsAddress("+15550001", PduHeaders.FROM, 106),
                MmsAddress("+15550002", PduHeaders.TO, 106),
            ),
        )

        assertThat(writer().writeMmsMessage(toOne).outcome).isEqualTo(WriteOutcome.ADDED)
        assertThat(writer().writeMmsMessage(toGroup).outcome).isEqualTo(WriteOutcome.ADDED)
        assertThat(writer().writeMmsMessage(toOne).outcome).isEqualTo(WriteOutcome.DUPLICATE)
        assertThat(writer().writeMmsMessage(toGroup).outcome).isEqualTo(WriteOutcome.DUPLICATE)
        assertThat(store.count("pdu")).isEqualTo(2)
        assertThat(store.count("part")).isEqualTo(4)
    }

    @Test
    fun twoSenderNamesOfTheSameLength_areTwoSenders() {
        val store = FakeMessageStore.install()
        fun from(name: String) = listOf(
            MmsAddress(name, PduHeaders.FROM, 106),
            MmsAddress("+15550002", PduHeaders.TO, 106),
            MmsAddress("+15550003", PduHeaders.TO, 106),
        )
        writer().writeMmsMessage(mms(addresses = from("BANKA")))

        assertThat(writer().writeMmsMessage(mms(addresses = from("SHOPB"))).outcome).isEqualTo(WriteOutcome.ADDED)
        assertThat(writer().writeMmsMessage(mms(addresses = from("banka"))).outcome).isEqualTo(WriteOutcome.DUPLICATE)
        assertThat(store.count("pdu")).isEqualTo(2)
    }

    @Test
    fun theSameTransactionId_picksItsOwnMessage_andAnotherIdIsAnotherMessage() {
        val store = FakeMessageStore.install()
        val one = store.threadIdFor("+15550001", "+15550003")
        val other = store.threadIdFor("+15550001234")
        val first = store.addMms(one, 1_000, Mms.MESSAGE_BOX_INBOX, "look", photo, groupPairs)
        val second = store.addMms(other, 1_000, Mms.MESSAGE_BOX_INBOX, "look", null, groupPairs)
        store.setTransactionId(first, "T-1")
        store.setTransactionId(second, "T-2")

        // T-2 is the one without its picture: the picture goes to it, not to T-1.
        val repaired = writer().writeMmsMessage(mms(transactionId = "T-2"))
        val added = writer().writeMmsMessage(mms(transactionId = "T-3"))

        assertThat(repaired.outcome).isEqualTo(WriteOutcome.REPAIRED)
        assertThat(store.count("part", "mid = ?", second.toString())).isEqualTo(2)
        assertThat(store.count("part", "mid = ?", first.toString())).isEqualTo(2)
        assertThat(added.outcome).isEqualTo(WriteOutcome.ADDED)
        assertThat(store.count("pdu")).isEqualTo(3)
    }

    @Test
    fun twoStoredMessagesThatBothFit_areNotGuessedBetween() {
        // Two copies stored in two other conversations, no transaction ids: neither is taken; the message goes to
        // its own conversation, and from then on that one is found.
        val store = FakeMessageStore.install()
        store.addMms(store.threadIdFor("+15550001"), 1_000, Mms.MESSAGE_BOX_INBOX, "look", photo, groupPairs)
        store.addMms(store.threadIdFor("+15550003"), 1_000, Mms.MESSAGE_BOX_INBOX, "look", photo, groupPairs)

        assertThat(writer().writeMmsMessage(mms()).outcome).isEqualTo(WriteOutcome.ADDED)
        assertThat(writer().writeMmsMessage(mms()).outcome).isEqualTo(WriteOutcome.DUPLICATE)
        assertThat(store.count("pdu")).isEqualTo(3)
    }

    @Test
    fun aMessageWithoutASender_isOnlyLookedForInItsOwnConversation() {
        val store = FakeMessageStore.install()
        val noSender = listOf(
            MmsAddress("insert-address-token", PduHeaders.FROM, 106),
            MmsAddress("+15550002", PduHeaders.TO, 106),
            MmsAddress("+15550003", PduHeaders.TO, 106),
        )
        // The same time and text without a sender in another conversation is not this message.
        store.addMms(
            store.threadIdFor("+15550002"), 1_000, Mms.MESSAGE_BOX_INBOX, "look", photo,
            noSender.map { it.address to it.type },
        )

        assertThat(writer().writeMmsMessage(mms(addresses = noSender)).outcome).isEqualTo(WriteOutcome.ADDED)
        assertThat(writer().writeMmsMessage(mms(addresses = noSender)).outcome).isEqualTo(WriteOutcome.DUPLICATE)
        assertThat(store.count("pdu")).isEqualTo(2)
    }

    @Test
    fun aGroupMmsInAnotherConversation_getsItsMissingPicture() {
        val store = FakeMessageStore.install()
        val group = store.threadIdFor("+15550001", "+15550003")
        val id = store.addMms(group, 1_000, Mms.MESSAGE_BOX_INBOX, "look", null, groupPairs)

        val result = writer(own = emptyList()).writeMmsMessage(mms())

        assertThat(result.outcome).isEqualTo(WriteOutcome.REPAIRED)
        assertThat(store.count("pdu")).isEqualTo(1)
        val picture = store.rows("part").single { it["ct"] == "image/jpeg" }
        assertThat(picture["mid"]).isEqualTo(id)
        assertThat(store.partBytes(picture["_id"] as Long)!!.toList()).isEqualTo(photo.toList())
    }

    @Test
    fun whenTheMmsListCannotBeRead_theConversationsOwnCheckStillHolds_andOneLineSaysSo() {
        val store = FakeMessageStore.install()
        writer(own = listOf("+15550002")).writeMmsMessage(mms())
        store.failMmsList = true
        ShadowLog.clear()

        val again = writer(own = listOf("+15550002")).writeMmsMessage(mms())
        val other = writer().writeMmsMessage(mms(text = "new"))

        assertThat(again.outcome).isEqualTo(WriteOutcome.DUPLICATE)
        assertThat(other.outcome).isEqualTo(WriteOutcome.ADDED)
        assertThat(store.count("pdu")).isEqualTo(2)
        // One line per restore (each writer is one), without text or numbers.
        val lines = ShadowLog.getLogsForTag("uMessageRestore").map { it.msg }
        assertThat(lines).hasSize(2)
        assertThat(lines.joinToString()).doesNotContain("look")
        assertThat(lines.joinToString()).doesNotContain("+1555")
    }

    @Test
    fun anSmsWithoutASender_goesToTheUnknownChatOnce() {
        val store = FakeMessageStore.install()

        val results = listOf("", " ", "\t", "unknown", "Unknown").map {
            writer().writeSmsMessage(sms(address = it, body = "no sender")).outcome
        }

        assertThat(results).containsExactly(
            WriteOutcome.ADDED, WriteOutcome.DUPLICATE, WriteOutcome.DUPLICATE, WriteOutcome.DUPLICATE,
            WriteOutcome.DUPLICATE,
        ).inOrder()
        val row = store.rows("sms").single()
        assertThat(row["address"]).isEqualTo("Unknown")
        assertThat(store.threadRecipients(row["thread_id"] as Long)).containsExactly("Unknown")
    }

    @Test
    fun oneToOneMms_staysWithTheSender() {
        val store = FakeMessageStore.install()
        val addresses = listOf(MmsAddress("+15550001", PduHeaders.FROM, 106), MmsAddress("+15559999", PduHeaders.TO, 106))
        writer().writeMmsMessage(mms(addresses = addresses))
        val thread = store.rows("pdu").single()["thread_id"] as Long
        assertThat(store.threadRecipients(thread)).containsExactly("+15550001")
    }

    @Test
    fun sentGroupMms_goesToAllItsRecipients() {
        val store = FakeMessageStore.install()
        val addresses = listOf(
            MmsAddress("insert-address-token", PduHeaders.FROM, 106),
            MmsAddress("+15550002", PduHeaders.TO, 106),
            MmsAddress("+15550003", PduHeaders.TO, 106),
        )
        writer().writeMmsMessage(mms(box = Mms.MESSAGE_BOX_SENT, addresses = addresses))
        val thread = store.rows("pdu").single()["thread_id"] as Long
        assertThat(store.threadRecipients(thread)).containsExactly("+15550002", "+15550003")
    }

    @Test
    fun attachmentThatFailsToSave_isReportedLeftOutAndRetriedNextTime() {
        val store = FakeMessageStore.install()
        store.failAllPartWrites = true
        val first = writer().writeMmsMessage(mms())
        assertThat(first.outcome).isEqualTo(WriteOutcome.INCOMPLETE)
        assertThat(first.reason).isEqualTo(MessagesWriter.REASON_ATTACHMENT_WRITE)
        // no blank picture is left behind
        assertThat(store.count("part", "ct = ?", "image/jpeg")).isEqualTo(0)

        store.failAllPartWrites = false
        assertThat(writer().writeMmsMessage(mms()).outcome).isEqualTo(WriteOutcome.REPAIRED)
        val image = store.rows("part").single { it["ct"] == "image/jpeg" }
        assertThat(store.partBytes(image["_id"] as Long)).isEqualTo(photo)
        assertThat(store.count("pdu")).isEqualTo(1)
    }

    @Test
    fun attachmentWithBytesLostEarlier_isWrittenAgain() {
        val store = FakeMessageStore.install()
        writer().writeMmsMessage(mms())
        val image = store.rows("part").single { it["ct"] == "image/jpeg" }["_id"] as Long
        java.io.File(store.rows("part").single { it["_id"] == image }["_data"] as String).writeBytes(ByteArray(0))

        assertThat(writer().writeMmsMessage(mms()).outcome).isEqualTo(WriteOutcome.REPAIRED)
        assertThat(store.partBytes(image)).isEqualTo(photo)
        assertThat(store.count("part")).isEqualTo(2)
    }

    @Test
    fun attachmentMissingFromTheBackup_isNotAddedAsABlankPicture() {
        val store = FakeMessageStore.install()
        val result = writer().writeMmsMessage(mms(image = null, imageData = ""))
        assertThat(result.outcome).isEqualTo(WriteOutcome.INCOMPLETE)
        assertThat(result.reason).isEqualTo(MessagesWriter.REASON_NOT_IN_BACKUP)
        assertThat(store.count("part", "ct = ?", "image/jpeg")).isEqualTo(0)
        assertThat(store.count("part", "ct = ?", "text/plain")).isEqualTo(1)
    }

    @Test
    fun damagedAttachmentData_isReported() {
        FakeMessageStore.install()
        val result = writer().writeMmsMessage(mms(imageData = "***not base64***"))
        assertThat(result.outcome).isEqualTo(WriteOutcome.INCOMPLETE)
        assertThat(result.reason).isEqualTo(MessagesWriter.REASON_DAMAGED_ATTACHMENT)
    }

    @Test
    fun twoDifferentMmsInTheSameSecond_areBothKept() {
        val store = FakeMessageStore.install()
        writer().writeMmsMessage(mms(text = "first"))
        assertThat(writer().writeMmsMessage(mms(text = "second")).outcome).isEqualTo(WriteOutcome.ADDED)
        assertThat(store.count("pdu")).isEqualTo(2)
    }

    // ---- SMS duplicates (DL-30) ----------------------------------------------------------------------------------

    @Test
    fun twoCodesInTheSameMillisecond_areBothKept() {
        val store = FakeMessageStore.install()
        val w = writer()
        w.writeSmsMessage(sms(body = "Code 1234"))
        assertThat(w.writeSmsMessage(sms(body = "Code 5678")).outcome).isEqualTo(WriteOutcome.ADDED)
        assertThat(store.count("sms")).isEqualTo(2)
    }

    @Test
    fun theSameNumberInTwoFormats_isOnePerson() {
        val store = FakeMessageStore.install()
        writer().writeSmsMessage(sms(address = "+447121234567"))
        assertThat(writer().writeSmsMessage(sms(address = "07121234567")).outcome).isEqualTo(WriteOutcome.DUPLICATE)
        assertThat(store.count("sms")).isEqualTo(1)
    }

    @Test
    fun smsAlreadyOnThePhone_isRecognized() {
        val store = FakeMessageStore.install()
        store.addSms("+15550001", "hi", 1_000)
        assertThat(writer().writeSmsMessage(sms()).outcome).isEqualTo(WriteOutcome.DUPLICATE)
    }

    // ---- import summary (DL-29, DL-34, DL-35) --------------------------------------------------------------------

    @Test
    fun importingTheSameFileTwice_saysNothingNew() {
        val store = FakeMessageStore.install()
        val entries = arrayOf<Any>(sms(date = 1), sms(date = 2), mms())
        val first = importer().import(file(*entries), all)
        assertThat(first.added).isEqualTo(3)

        val second = importer().import(file(*entries), all)
        assertThat(second.added).isEqualTo(0)
        assertThat(second.duplicates).isEqualTo(3)
        assertThat(second.changedAnything).isFalse()
        assertThat(BackupSummaryText.import(context, second)).contains("Nothing new to import")
        assertThat(store.count("sms")).isEqualTo(2)
        assertThat(store.count("part")).isEqualTo(2)
    }

    @Test
    fun oneDamagedEntry_doesNotStopTheRest() {
        val store = FakeMessageStore.install()
        val summary = importer().import(
            file(sms(date = 1), """{"backupType":"sms","address":"x"}""", """{"backupType":"fax"}""", sms(date = 2)), all,
        )
        assertThat(summary.added).isEqualTo(2)
        assertThat(summary.damaged).isEqualTo(2)
        assertThat(summary.invalidFile).isFalse()
        assertThat(store.count("sms")).isEqualTo(2)
    }

    @Test
    fun aFileThatEndsEarly_keepsWhatCameBeforeAndSaysSo() {
        val store = FakeMessageStore.install()
        val text = file(sms(date = 1), sms(date = 2), sms(date = 3)).readBytes().decodeToString()
        val cut = text.substring(0, text.lastIndexOf("{\"") + 10)
        val summary = importer().import(cut.byteInputStream(), all)
        assertThat(summary.stoppedEarly).isTrue()
        assertThat(summary.added).isEqualTo(2)
        assertThat(store.count("sms")).isEqualTo(2)
        assertThat(BackupSummaryText.import(context, summary)).contains("damaged after entry 2")
    }

    @Test
    fun aFileThatIsNotABackup_isRejected() {
        FakeMessageStore.install()
        val summary = importer().import("hello there".byteInputStream(), all)
        assertThat(summary.invalidFile).isTrue()
        assertThat(BackupSummaryText.import(context, summary)).contains("not a message backup")
    }

    @Test
    fun typesNotChosen_areCountedAsNotSelected() {
        val store = FakeMessageStore.install()
        val summary = importer().import(file(sms(), mms()), MessagesImporter.Options(sms = true, mms = false))
        assertThat(summary.added).isEqualTo(1)
        assertThat(summary.skippedByChoice).isEqualTo(1)
        assertThat(store.count("pdu")).isEqualTo(0)
    }

    @Test
    fun xmlBackup_givesOneSummaryWithBadRowsAndAPictureMessageWithoutAddressFailing() {
        val store = FakeMessageStore.install()
        val xml = """
            <?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
            <smses count="5">
              <sms protocol="0" address="+15550001" date="1000" type="1" body="one" read="1" status="-1" locked="0" />
              <sms protocol="0" address="+15550001" date="oops" type="1" body="bad date" />
              <sms address="" date="3000" type="1" body="no address" />
              <mms date="4000" msg_box="1"><parts><part ct="image/jpeg" /></parts></mms>
              <sms address="+15550002" date="5000" type="2" body="سلام 👋" />
            </smses>
        """.trimIndent()
        val summary = importer().import(xml.byteInputStream(), all)
        // The text without a sender is kept in the "Unknown" chat; only the bad date is damaged.
        assertThat(summary.added).isEqualTo(3)
        assertThat(summary.damaged).isEqualTo(1)
        assertThat(summary.failed).isEqualTo(1)
        assertThat(store.rows("sms").map { it["body"] }).containsExactly("one", "no address", "سلام 👋").inOrder()
        val text = BackupSummaryText.import(context, summary)
        assertThat(text).contains("Added: 3")
        assertThat(text).contains("Could not be restored: 1")
    }

    @Test
    fun xmlBackup_anSmsWithoutASender_goesToTheUnknownChatOnce() {
        val store = FakeMessageStore.install()
        val xml = """
            <?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
            <smses count="4">
              <sms address="" date="3000" type="1" body="no sender" />
              <sms address=" " date="3000" type="1" body="no sender" />
              <sms address="null" date="3000" type="1" body="no sender" />
              <sms date="3000" type="1" body="no sender" />
            </smses>
        """.trimIndent()

        val first = importer().import(xml.byteInputStream(), all)
        val again = importer().import(xml.byteInputStream(), all)

        assertThat(first.added).isEqualTo(1)
        assertThat(first.duplicates).isEqualTo(3)
        assertThat(first.damaged).isEqualTo(0)
        assertThat(again.added).isEqualTo(0)
        assertThat(again.duplicates).isEqualTo(4)
        val row = store.rows("sms").single()
        assertThat(row["address"]).isEqualTo("Unknown")
        assertThat(store.threadRecipients(row["thread_id"] as Long)).containsExactly("Unknown")
    }

    private val xmlPhoto = Base64.getEncoder().encodeToString(photo)

    private fun xmlMms(parts: String, attrs: String = "date=\"4000000000000\" msg_box=\"1\" m_type=\"132\"") = """
        <?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
        <smses count="1">
          <mms $attrs address="+15550001~+15550002">
            <parts>$parts</parts>
            <addrs>
              <addr address="+15550001" type="137" charset="106" />
              <addr address="+15550002" type="151" charset="106" />
            </addrs>
          </mms>
        </smses>
    """.trimIndent()

    @Test
    fun xmlBackup_restoresAPictureMessageWithItsAttachment_andSkipsItTheSecondTime() {
        val store = FakeMessageStore.install()
        val xml = xmlMms(
            "<part seq=\"0\" ct=\"text/plain\" text=\"look\" />" +
                "<part seq=\"1\" ct=\"image/jpeg\" name=\"p.jpg\" data=\"$xmlPhoto\" />",
        )
        val first = importer().import(xml.byteInputStream(), all)
        assertThat(first.added).isEqualTo(1)
        assertThat(first.unsupportedMms).isEqualTo(0)
        assertThat(store.count("pdu")).isEqualTo(1)
        assertThat(store.count("part")).isEqualTo(2)
        // SMS Backup & Restore writes MMS times in milliseconds; the provider keeps seconds.
        assertThat(store.rows("pdu").single()["date"]).isEqualTo(4_000_000_000L)
        val image = store.rows("part").single { it["ct"] == "image/jpeg" }["_id"] as Long
        assertThat(store.partBytes(image)).isEqualTo(photo)

        val second = importer().import(xml.byteInputStream(), all)
        assertThat(second.added).isEqualTo(0)
        assertThat(second.duplicates).isEqualTo(1)
        assertThat(store.count("pdu")).isEqualTo(1)
        assertThat(store.count("part")).isEqualTo(2)
    }

    @Test
    fun xmlBackup_pictureMessagesAreLeftOut_whenOnlySmsIsChosen() {
        val store = FakeMessageStore.install()
        val xml = xmlMms("<part seq=\"0\" ct=\"text/plain\" text=\"look\" />")
        val summary = importer().import(xml.byteInputStream(), MessagesImporter.Options(sms = true, mms = false))
        assertThat(summary.skippedByChoice).isEqualTo(1)
        assertThat(store.count("pdu")).isEqualTo(0)
    }

    @Test
    fun xmlBackup_anAttachmentOverTheSizeLimit_isLeftOutAndTheMessageSaysSo() {
        val store = FakeMessageStore.install()
        val huge = "A".repeat(19_000_001)
        val xml = xmlMms(
            "<part seq=\"0\" ct=\"text/plain\" text=\"look\" /><part seq=\"1\" ct=\"image/jpeg\" data=\"$huge\" />",
        )
        val summary = importer().import(xml.byteInputStream(), all)
        assertThat(summary.incomplete).isEqualTo(1)
        assertThat(store.rows("part").map { it["ct"] }).containsExactly("text/plain")
    }

    @Test
    fun xmlBackup_aPictureMessageWithoutDate_isDamaged_andTheNextEntriesStillImport() {
        val store = FakeMessageStore.install()
        val xml = """
            <?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
            <smses count="2">
              <mms msg_box="1"><parts><part ct="text/plain" text="x" /></parts></mms>
              <sms address="+15550001" date="1000" type="1" body="after" />
            </smses>
        """.trimIndent()
        val summary = importer().import(xml.byteInputStream(), all)
        assertThat(summary.damaged).isEqualTo(1)
        assertThat(summary.added).isEqualTo(1)
        assertThat(store.rows("sms").map { it["body"] }).containsExactly("after")
    }

    @Test
    fun byteOrderMark_isIgnored() {
        FakeMessageStore.install()
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + file(sms()).readBytes()
        assertThat(importer().import(bytes.inputStream(), all).added).isEqualTo(1)
    }

    // ---- streaming, speed, interruption (DL-31, DL-32, DL-36) ---------------------------------------------------

    @Test
    fun aLargeFile_isReadAsAStream() {
        val store = FakeMessageStore.install()
        val count = 3_000
        // The file is generated as it is read, so it is never in memory as a whole.
        val parts = (0 until count).asSequence().map { i ->
            ((if (i == 0) "[" else ",") + json.encodeToString(SmsBackup.serializer(), sms(date = i.toLong(), body = "m$i")))
                .byteInputStream() as InputStream
        } + sequenceOf("]".byteInputStream())
        val stream = SequenceInputStream(Collections.enumeration(parts.toList()))
        val progress = mutableListOf<Int>()
        val summary = importer().import(stream, all, progress = { entries, _ -> progress += entries })
        assertThat(summary.added).isEqualTo(count)
        assertThat(store.count("sms")).isEqualTo(count)
        assertThat(progress.last()).isEqualTo(count)
    }

    @Test
    fun restoringManySmsOfOneConversation_asksTheProviderOnlyAFewTimes() {
        val store = FakeMessageStore.install()
        val entries = (1..200).map { sms(date = it.toLong(), body = "m$it") }.toTypedArray<Any>()
        val before = store.queries
        importer().import(file(*entries), all)
        // The old writer asked about every message several times (thread, duplicate check): 400+ queries here.
        assertThat(store.queries - before).isLessThan(20)
    }

    @Test
    fun aStoppedRestore_canBeRunAgainToFinish() {
        val store = FakeMessageStore.install()
        val entries = (1..100).map { sms(date = it.toLong(), body = "m$it") }.toTypedArray<Any>()
        var checks = 0
        val stopped = importer().import(file(*entries), all, isCancelled = { ++checks > 40 })
        assertThat(stopped.cancelled).isTrue()
        assertThat(stopped.added).isEqualTo(40)
        assertThat(BackupSummaryText.import(context, stopped)).contains("Run the restore again")

        val again = importer().import(file(*entries), all)
        assertThat(again.duplicates).isEqualTo(40)
        assertThat(again.added).isEqualTo(60)
        assertThat(store.count("sms")).isEqualTo(100)
    }

    @Test
    fun aRunTheSystemKilled_isReportedOnce() {
        context.getSharedPreferences("backup_job", Context.MODE_PRIVATE).edit().putString("running", "IMPORT").commit()
        assertThat(BackupJob.takeInterruptedRun(context)).isEqualTo(BackupJob.Kind.IMPORT)
        assertThat(BackupJob.takeInterruptedRun(context)).isNull()
    }

    // ---- export (DL-32, DL-33) and the round trip (QD-04, DL-50) -------------------------------------------------

    @Test
    fun export_countsUnreadableAttachmentsAndKeepsTheMessage() {
        val store = FakeMessageStore.install()
        val thread = store.threadIdFor("+15550001")
        store.addMms(thread, 100, Mms.MESSAGE_BOX_INBOX, "pic", photo, listOf("+15550001" to PduHeaders.FROM))
        store.unreadableParts += store.rows("part").single { it["ct"] == "image/jpeg" }["_id"] as Long

        val out = ByteArrayOutputStream()
        val summary = MessagesReader(context).export(out, sms = true, mms = true)
        assertThat(summary.mms).isEqualTo(1)
        assertThat(summary.unreadableAttachments).isEqualTo(1)
        assertThat(BackupSummaryText.export(context, summary, null)).contains("could not be read")

        // Restoring that file says the picture is missing instead of showing a blank one.
        val target = FakeMessageStore.install()
        val restored = importer().import(out.toByteArray().inputStream(), all)
        assertThat(restored.incomplete).isEqualTo(1)
        assertThat(restored.reasons).containsKey(MessagesWriter.REASON_NOT_IN_BACKUP)
        assertThat(target.count("part", "ct = ?", "image/jpeg")).isEqualTo(0)
    }

    @Test
    fun exportThenImport_restoresEverythingExactly() {
        val source = FakeMessageStore.install()
        val ali = source.threadIdFor("+447121111111")
        source.addSms("+447121111111", "see you at 5 🙂", 2_000, type = 2, threadId = ali)
        source.addSms("+447121111111", "سلام، خوبی؟", 1_000, type = 1, threadId = ali)
        source.addSms("+447122222222", "call me", 3_000)
        source.addSms("+447122222222", null, 4_000)
        val group = source.threadIdFor("+15550001", "+15550002", "+15550003")
        source.addMms(
            group, 5, Mms.MESSAGE_BOX_INBOX, "group photo", photo,
            listOf("+15550001" to PduHeaders.FROM, "+15550002" to PduHeaders.TO, "+15550003" to PduHeaders.TO),
        )

        val out = ByteArrayOutputStream()
        val exported = MessagesReader(context).export(out, sms = true, mms = true)
        assertThat(exported.sms).isEqualTo(4)
        assertThat(exported.mms).isEqualTo(1)
        assertThat(exported.hasProblems).isFalse()

        val target = FakeMessageStore.install()
        val summary = importer().import(out.toByteArray().inputStream(), all)
        assertThat(summary.toString()).isEqualTo(ImportSummary().apply { added = 5; entriesRead = 5 }.toString())

        val sms = target.rows("sms", orderBy = "date")
        assertThat(sms.map { it["body"] }).containsExactly("سلام، خوبی؟", "see you at 5 🙂", "call me", null).inOrder()
        assertThat(sms.map { it["type"] }).containsExactly(1L, 2L, 1L, 1L).inOrder()
        assertThat(target.threadRecipients(sms[0]["thread_id"] as Long)).containsExactly("+447121111111")

        val pdu = target.rows("pdu").single()
        assertThat(target.threadRecipients(pdu["thread_id"] as Long))
            .containsExactly("+15550001", "+15550002", "+15550003")
        val parts = target.rows("part")
        assertThat(parts.single { it["ct"] == "text/plain" }["text"]).isEqualTo("group photo")
        assertThat(target.partBytes(parts.single { it["ct"] == "image/jpeg" }["_id"] as Long)).isEqualTo(photo)
        assertThat(target.count("addr")).isEqualTo(3)

        // and a second restore of the same file changes nothing
        val again = importer().import(out.toByteArray().inputStream(), all)
        assertThat(again.duplicates).isEqualTo(5)
        assertThat(target.count("part")).isEqualTo(2)
    }

    @Test
    fun export_writesValidJsonThatOlderReadersAccept() {
        val source = FakeMessageStore.install()
        source.addSms("+15550001", "hi", 1_000)
        val out = ByteArrayOutputStream()
        MessagesReader(context).export(out, sms = true, mms = true)
        // The old importer read the whole file as a list; the format must stay readable that way.
        val list = Json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(com.glooboo.umessage.models.MessagesBackup.serializer()),
            out.toString(Charsets.UTF_8.name()),
        )
        assertThat(list).hasSize(1)
    }
}

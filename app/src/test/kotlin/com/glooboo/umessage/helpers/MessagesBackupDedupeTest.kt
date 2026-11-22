package com.glooboo.umessage.helpers

import android.content.ContentValues
import android.content.Context
import android.provider.Telephony.Mms
import android.provider.Telephony.Sms
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.models.MmsAddress
import com.glooboo.umessage.models.MmsBackup
import com.glooboo.umessage.models.MmsPart
import com.glooboo.umessage.models.SmsBackup
import com.glooboo.umessage.models.WriteOutcome
import com.glooboo.umessage.testing.FakeMessageStore
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.google.android.mms.pdu_alt.PduHeaders
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowLog
import java.util.Base64

/**
 * J15 (emulator run 12): a restored message the store already has is found whatever conversation it is in, so a
 * phone that does not tell its own number gets no second copy; two different messages are never merged; a message
 * without a readable sender goes to the "Unknown" chat once. Every number is made up.
 */
@RunWith(AndroidJUnit4::class)
class MessagesBackupDedupeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val photo = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)

    @After
    fun tearDown() = resetSharedMessagesDatabase()

    private fun writer(own: List<String> = emptyList()) = MessagesWriter(context) { own }
    private fun importer() = MessagesImporter(context, writer())
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
        transactionId: String? = null,
    ) = MmsBackup(
        creator = null, contentType = "application/vnd.wap.multipart.related", deliveryReport = 0, date = date,
        dateSent = 0, locked = 0, messageType = 132, messageBox = box, read = 1, readReport = 0, seen = 1,
        textOnly = if (image == null) 1 else 0, status = null, subject = null, subjectCharSet = null,
        subscriptionId = -1, transactionId = transactionId, addresses = addresses,
        parts = listOfNotNull(
            text?.let { MmsPart(null, "106", null, null, "text/plain", null, null, null, null, 0, it, null) },
            image?.let {
                val data = Base64.getEncoder().encodeToString(it)
                MmsPart(null, null, null, "photo.jpg", "image/jpeg", null, null, null, "photo.jpg", 0, null, data)
            },
        ),
    )

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
    fun anSmsWithoutASender_isAlreadyThereWhenStoredWithABlankSenderInAnyConversation() {
        // J15-10: the store keeps such texts under null, blank or "Unknown", in whatever conversation.
        val store = FakeMessageStore.install()
        store.addSms("", "blank", date = 1_000, threadId = store.threadIdFor("+15550001234"))
        store.addSms(" Unknown ", "placeholder", date = 2_000, threadId = store.threadIdFor("+15550001"))
        context.contentResolver.insert(
            Sms.CONTENT_URI,
            ContentValues().apply {
                putNull(Sms.ADDRESS); put(Sms.BODY, "null sender"); put(Sms.DATE, 3_000L); put(Sms.TYPE, 1)
                put(Sms.THREAD_ID, store.threadIdFor("+15550002"))
            },
        )

        val again = listOf(
            sms(address = "", body = "blank", date = 1_000),
            sms(address = "\t", body = "placeholder", date = 2_000),
            sms(address = "unknown", body = "null sender", date = 3_000),
        ).map { writer().writeSmsMessage(it).outcome }
        val other = writer().writeSmsMessage(sms(address = "", body = "blank", date = 1_000, type = 2)).outcome

        assertThat(again).containsExactly(WriteOutcome.DUPLICATE, WriteOutcome.DUPLICATE, WriteOutcome.DUPLICATE)
        // Another type is another message.
        assertThat(other).isEqualTo(WriteOutcome.ADDED)
        assertThat(store.count("sms")).isEqualTo(4)
    }

    @Test
    fun aReceivedMmsWithoutASender_goesToTheUnknownChatOnce() {
        val store = FakeMessageStore.install()
        val noSender = listOf(
            MmsAddress("insert-address-token", PduHeaders.FROM, 106),
            MmsAddress("+15550002", PduHeaders.TO, 106),
        )

        val first = writer().writeMmsMessage(mms(addresses = noSender))
        val again = writer().writeMmsMessage(mms(addresses = noSender))
        val withoutFrom = writer().writeMmsMessage(mms(text = "other", addresses = noSender.drop(1)))

        assertThat(first.outcome).isEqualTo(WriteOutcome.ADDED)
        assertThat(again.outcome).isEqualTo(WriteOutcome.DUPLICATE)
        assertThat(withoutFrom.outcome).isEqualTo(WriteOutcome.ADDED)
        assertThat(store.count("pdu")).isEqualTo(2)
        store.rows("pdu").forEach {
            assertThat(store.threadRecipients(it["thread_id"] as Long)).containsExactly("Unknown")
        }
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
}

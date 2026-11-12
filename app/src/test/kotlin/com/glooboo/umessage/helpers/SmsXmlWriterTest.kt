package com.glooboo.umessage.helpers

import android.content.Context
import android.provider.Telephony.Mms
import android.util.Xml
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.models.SmsBackup
import com.glooboo.umessage.testing.FakeMessageStore
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.google.android.mms.pdu_alt.PduHeaders
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.io.StringWriter

/** The "SMS Backup & Restore" XML: escaping, the attributes other apps read, and a round trip through the importer. */
@RunWith(AndroidJUnit4::class)
class SmsXmlWriterTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val all = MessagesImporter.Options(sms = true, mms = true)
    private val photo = byteArrayOf(9, 8, 7, 6)

    @After
    fun tearDown() = resetSharedMessagesDatabase()

    private fun sms(
        address: String = "+15550001", body: String? = "hi", date: Long = 1_700_000_000_000, type: Int = 1,
    ) =
        SmsBackup(-1, address, body, date, date - 1_000, 0, "0", 1, -1, type, null)

    private fun write(count: Int, vararg entries: SmsBackup, names: Map<String, String> = emptyMap()): String {
        val out = StringWriter()
        SmsXmlWriter(out, names::get, now = { 42L }, backupSet = "set-1").apply {
            begin(count)
            entries.forEach(::write)
            end()
        }
        return out.toString()
    }

    private class Element(val name: String, val attributes: Map<String, String>)

    private fun parse(xml: String): List<Element> {
        val parser = Xml.newPullParser()
        parser.setInput(xml.reader())
        val elements = mutableListOf<Element>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                val attributes = (0 until parser.attributeCount)
                    .associate { parser.getAttributeName(it) to parser.getAttributeValue(it) }
                elements += Element(parser.name, attributes)
            }
        }
        return elements
    }

    // ---- escaping ---------------------------------------------------------------------------------------------

    @Test
    fun escape_handlesTheFiveXmlCharactersAndLineBreaks() {
        assertThat(SmsXmlWriter.escape("a & b < c > d \" e ' f")).isEqualTo("a &amp; b &lt; c &gt; d &quot; e &apos; f")
        assertThat(SmsXmlWriter.escape("one\ntwo\r\n\tend")).isEqualTo("one&#10;two&#13;&#10;&#9;end")
    }

    @Test
    fun escape_keepsEmojiAndPersian_andDropsWhatXmlCannotHold() {
        assertThat(SmsXmlWriter.escape("سلام 👋 ی")).isEqualTo("سلام 👋 ی")
        assertThat(SmsXmlWriter.escape("a\u0000b\u0008c￾d")).isEqualTo("abcd")
        assertThat(SmsXmlWriter.escape("lonely \uD83D surrogate")).isEqualTo("lonely  surrogate")
    }

    // ---- the file ---------------------------------------------------------------------------------------------

    @Test
    fun theFile_isWellFormedWithTheCountAndTheStandardAttributes() {
        val xml = write(
            2,
            sms(body = "say \"hi\" & <go>", type = 2),
            sms(address = "+447121111111", body = "سلام 👋"),
            names = mapOf("+15550001" to "Sam Q."))
        val elements = parse(xml)
        val root = elements.first()
        assertThat(root.name).isEqualTo("smses")
        assertThat(root.attributes).containsEntry("count", "2")
        assertThat(root.attributes).containsEntry("backup_set", "set-1")
        assertThat(root.attributes).containsEntry("backup_date", "42")
        val first = elements[1]
        assertThat(first.name).isEqualTo("sms")
        assertThat(first.attributes).containsAtLeast(
            "protocol", "0", "address", "+15550001", "date", "1700000000000", "type", "2",
            "body", "say \"hi\" & <go>", "read", "1", "date_sent", "1699999999000", "sub_id", "-1",
            "contact_name", "Sam Q.", "status", "-1", "locked", "0",
        )
        assertThat(first.attributes["readable_date"])
            .matches("[A-Z][a-z]{2} \\d{1,2}, \\d{4} \\d{1,2}:\\d{2}:\\d{2} [AP]M")
        val second = elements[2]
        assertThat(second.attributes).containsEntry("body", "سلام 👋")
        assertThat(second.attributes).containsEntry("contact_name", "(Unknown)")
    }

    @Test
    fun aMissingBody_isWrittenAsTheTextNull_asTheOriginalAppDoes() {
        val xml = write(1, sms(body = null))
        assertThat(parse(xml)[1].attributes).containsEntry("body", "null")
    }

    // ---- round trips ------------------------------------------------------------------------------------------

    @Test
    fun writtenXml_isRestoredByTheImporter() {
        val store = FakeMessageStore.install()
        val xml = write(
            3,
            sms(body = "plain text", date = 1_000),
            sms(
                address = "+447122222222", date = 2_000, type = 2,
                body = "line one\nline two & \"quotes\" سلام 👋",
            ),
            sms(body = "third", date = 3_000),
        )
        val summary = MessagesImporter(context, MessagesWriter(context) { emptyList() })
            .import(xml.byteInputStream(), all)
        assertThat(summary.added).isEqualTo(3)
        assertThat(summary.damaged).isEqualTo(0)
        val rows = store.rows("sms", orderBy = "date")
        assertThat(rows.map { it["body"] })
            .containsExactly(
            "plain text", "line one\nline two & \"quotes\" سلام 👋", "third",
        ).inOrder()
        assertThat(rows.map { it["type"] }).containsExactly(1L, 2L, 1L).inOrder()
    }

    @Test
    fun readerExport_asXml_holdsSmsAndMms_andTheImporterRestoresBoth() {
        val source = FakeMessageStore.install()
        val ali = source.threadIdFor("+447121111111")
        source.addSms("+447121111111", "see you at 5 🙂", 2_000, type = 2, threadId = ali)
        source.addSms("+447121111111", "سلام، خوبی؟", 1_000, type = 1, threadId = ali)
        val group = source.threadIdFor("+15550001", "+15550002")
        source.addMms(
            group, 5, Mms.MESSAGE_BOX_INBOX, "group photo", photo,
            listOf("+15550001" to PduHeaders.FROM, "+15550002" to PduHeaders.TO),
        )

        val out = ByteArrayOutputStream()
        val exported = MessagesReader(context).export(
            out, sms = true, mms = true, format = ExportFormat.SMS_XML, contactName = { "Name of $it" },
        )
        assertThat(exported.sms).isEqualTo(2)
        assertThat(exported.mms).isEqualTo(1)
        val xml = out.toString(Charsets.UTF_8.name())
        val elements = parse(xml)
        assertThat(elements.first().attributes).containsEntry("count", "3")
        assertThat(elements.count { it.name == "sms" }).isEqualTo(2)
        val mms = elements.single { it.name == "mms" }
        assertThat(mms.attributes).containsEntry("address", "+15550001~+15550002")
        assertThat(mms.attributes).containsEntry("msg_box", "1")
        assertThat(elements.filter { it.name == "part" }.map { it.attributes["ct"] })
            .containsAtLeast("text/plain", "image/jpeg")
        assertThat(elements.count { it.name == "addr" }).isEqualTo(2)

        val target = FakeMessageStore.install()
        val summary = MessagesImporter(context, MessagesWriter(context) { emptyList() })
            .import(xml.byteInputStream(), all)
        assertThat(summary.added).isEqualTo(3)
        assertThat(summary.unsupportedMms).isEqualTo(0)
        assertThat(target.count("pdu")).isEqualTo(1)
        assertThat(target.rows("sms", orderBy = "date").map { it["body"] })
            .containsExactly("سلام، خوبی؟", "see you at 5 🙂").inOrder()
    }

    @Test
    fun readerExport_asXml_ofAConversationSubset_leavesTheOthersOut() {
        val source = FakeMessageStore.install()
        val one = source.threadIdFor("+15550001")
        val two = source.threadIdFor("+15550002")
        source.addSms("+15550001", "first thread", 1_000, threadId = one)
        source.addSms("+15550002", "second thread", 2_000, threadId = two)

        val out = ByteArrayOutputStream()
        val summary = MessagesReader(context).export(
            out, sms = true, mms = true, conversationIds = listOf(two), format = ExportFormat.SMS_XML,
        )
        assertThat(summary.sms).isEqualTo(1)
        val xml = out.toString(Charsets.UTF_8.name())
        assertThat(xml).contains("second thread")
        assertThat(xml).doesNotContain("first thread")
        assertThat(parse(xml).first().attributes).containsEntry("count", "1")
    }
}

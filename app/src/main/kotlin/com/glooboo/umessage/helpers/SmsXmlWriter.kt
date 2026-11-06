package com.glooboo.umessage.helpers

import com.glooboo.umessage.models.MessagesBackup
import com.glooboo.umessage.models.MmsAddress
import com.glooboo.umessage.models.MmsBackup
import com.glooboo.umessage.models.NotesBackup
import com.glooboo.umessage.models.MmsPart
import com.glooboo.umessage.models.SmsBackup
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Writes messages in the XML of "SMS Backup & Restore", so other apps can read the file: `<smses count=...>` holding
 * `<sms>` elements (address, date, type, body, read, date_sent, sub_id, readable_date, contact_name and the usual
 * others) and `<mms>` elements with their `<parts>` and `<addrs>`. Streamed one message at a time, no DOM, so a file
 * of any size is written. uMessage itself restores the texts from such a file; its own JSON is the format that also
 * restores pictures.
 *
 * [contactName] gives the saved name of a number (null when none); the app asked for the name only once per number.
 */
class SmsXmlWriter(
    private val out: Writer,
    private val contactName: (String) -> String? = { null },
    private val now: () -> Long = System::currentTimeMillis,
    private val backupSet: String = UUID.randomUUID().toString(),
) : BackupEntrySink {
    private val dateFormat = SimpleDateFormat("MMM d, yyyy h:mm:ss a", Locale.US)
    private val names = HashMap<String, String>()

    override fun begin(count: Int) {
        out.write("<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>\n")
        out.write("<smses count=\"$count\" backup_set=\"$backupSet\" backup_date=\"${now()}\" type=\"full\">\n")
    }

    override fun write(entry: MessagesBackup) {
        when (entry) {
            is SmsBackup -> writeSms(entry)
            is MmsBackup -> writeMms(entry)
            is NotesBackup -> Unit // the XML format of SMS Backup & Restore has no place for notes
        }
    }

    override fun end() {
        out.write("</smses>\n")
        out.flush()
    }

    private fun writeSms(sms: SmsBackup) {
        out.write(
            element(
                "sms",
                "protocol" to sms.protocol,
                "address" to sms.address,
                "date" to sms.date,
                "type" to sms.type,
                "subject" to null,
                "body" to sms.body,
                "toa" to null,
                "sc_toa" to null,
                "service_center" to sms.serviceCenter,
                "read" to sms.read,
                "status" to sms.status,
                "locked" to sms.locked,
                "date_sent" to sms.dateSent,
                "sub_id" to sms.subscriptionId,
                "readable_date" to readableDate(sms.date),
                "contact_name" to nameOf(sms.address),
                selfClosing = true,
            )
        )
        out.write("\n")
    }

    private fun writeMms(mms: MmsBackup) {
        val others = mms.addresses.map { it.address }.filter { it.isNotBlank() && it != ADDRESS_TOKEN }.distinct()
        out.write(
            element(
                "mms",
                "date" to mms.date,
                "rr" to mms.readReport,
                "sub" to mms.subject,
                "ct_t" to mms.contentType,
                "msg_box" to mms.messageBox,
                "address" to others.joinToString("~"),
                "sub_cs" to mms.subjectCharSet,
                "read" to mms.read,
                "st" to mms.status,
                "date_sent" to mms.dateSent,
                "d_rpt" to mms.deliveryReport,
                "m_type" to mms.messageType,
                "seen" to mms.seen,
                "tr_id" to mms.transactionId,
                "locked" to mms.locked,
                "text_only" to mms.textOnly,
                "sub_id" to mms.subscriptionId,
                "readable_date" to readableDate(mms.date),
                "contact_name" to others.joinToString(", ") { nameOf(it) },
                selfClosing = false,
            )
        )
        out.write("\n  <parts>\n")
        mms.parts.forEach { out.write("    ${part(it)}\n") }
        out.write("  </parts>\n  <addrs>\n")
        mms.addresses.forEach { out.write("    ${address(it)}\n") }
        out.write("  </addrs>\n</mms>\n")
    }

    private fun part(part: MmsPart) = element(
        "part",
        "seq" to part.sequenceOrder,
        "ct" to part.contentType,
        "name" to part.name,
        "chset" to part.charset,
        "cd" to part.contentDisposition,
        "fn" to part.filename,
        "cid" to part.contentId,
        "cl" to part.contentLocation,
        "ctt_s" to part.ctStart,
        "ctt_t" to part.ctType,
        "text" to part.text,
        "data" to part.data,
        selfClosing = true,
    )

    private fun address(address: MmsAddress) = element(
        "addr", "address" to address.address, "type" to address.type, "charset" to address.charset, selfClosing = true,
    )

    private fun nameOf(address: String): String =
        names.getOrPut(address) { contactName(address)?.takeIf { it.isNotBlank() } ?: UNKNOWN_NAME }

    /** The date the way the app writes it, in the phone's time zone. */
    private fun readableDate(millis: Long): String =
        dateFormat.format(Date(millis)).replace('\u202F', ' ') // newer Android puts a narrow space before AM/PM

    companion object {
        private const val UNKNOWN_NAME = "(Unknown)"
        private const val ADDRESS_TOKEN = "insert-address-token"
        private const val FIRST_VALID_CODE = 0x20
        private const val LAST_BMP_BEFORE_SURROGATES = 0xD7FF
        private const val FIRST_BMP_AFTER_SURROGATES = 0xE000
        private const val LAST_BMP_VALID = 0xFFFD
        private const val FIRST_SUPPLEMENTARY = 0x10000
        private const val LAST_CODE_POINT = 0x10FFFF

        /** `<name a="1" b="null" />`; a missing value is written as the text null, as the original app does. */
        fun element(name: String, vararg attributes: Pair<String, Any?>, selfClosing: Boolean): String =
            buildString {
                append('<').append(name)
                for ((key, value) in attributes) {
                    append(' ').append(key).append("=\"").append(escape(value?.toString() ?: "null")).append('"')
                }
                append(if (selfClosing) " />" else ">")
            }

        /**
         * Text safe inside a double-quoted attribute. Line breaks and tabs are written as character references so
         * a reader keeps them; characters XML 1.0 cannot hold at all (control codes, unpaired surrogates) are
         * dropped; emoji and Persian text go through as they are.
         */
        fun escape(text: String): String = buildString(text.length) {
            var index = 0
            while (index < text.length) {
                val code = text.codePointAt(index)
                index += Character.charCount(code)
                when {
                    code == '&'.code -> append("&amp;")
                    code == '<'.code -> append("&lt;")
                    code == '>'.code -> append("&gt;")
                    code == '"'.code -> append("&quot;")
                    code == '\''.code -> append("&apos;")
                    code == '\n'.code -> append("&#10;")
                    code == '\r'.code -> append("&#13;")
                    code == '\t'.code -> append("&#9;")
                    isXmlChar(code) -> appendCodePoint(code)
                }
            }
        }

        private fun isXmlChar(code: Int) = code in FIRST_VALID_CODE..LAST_BMP_BEFORE_SURROGATES ||
            code in FIRST_BMP_AFTER_SURROGATES..LAST_BMP_VALID || code in FIRST_SUPPLEMENTARY..LAST_CODE_POINT
    }
}

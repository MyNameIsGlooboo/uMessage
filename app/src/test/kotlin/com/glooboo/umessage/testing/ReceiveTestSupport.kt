package com.glooboo.umessage.testing

import android.content.Context
import android.content.Intent
import android.provider.Telephony
import androidx.room.Room
import com.glooboo.umessage.spam.FilterDecision
import com.glooboo.umessage.spam.IncomingMessage
import com.glooboo.umessage.spam.SpamFilter
import com.glooboo.umessage.spam.UMessage
import com.glooboo.umessage.spam.data.PendingSmsQueue
import com.glooboo.umessage.spam.data.SpamDatabase
import java.io.ByteArrayOutputStream

/** Builds SMS_DELIVER intents from real GSM PDUs, as telephony sends them to the default SMS app. */
object SmsPdus {

    /**
     * One SMS-DELIVER PDU (no SMSC, GSM 7-bit, at most 160 basic-alphabet characters). [sender] is a digit string,
     * optionally with a leading '+', or empty for a PDU without an originating address.
     */
    fun deliverPdu(sender: String, body: String): ByteArray {
        require(body.length <= 160)
        val out = ByteArrayOutputStream()
        out.write(0x00) // no SMSC
        out.write(0x04) // SMS-DELIVER, no more messages
        val digits = sender.removePrefix("+")
        out.write(digits.length)
        out.write(if (sender.startsWith("+")) 0x91 else 0x81)
        digits.chunked(2).forEach { pair ->
            val low = pair[0] - '0'
            val high = if (pair.length > 1) pair[1] - '0' else 0xF
            out.write((high shl 4) or low)
        }
        out.write(0x00) // TP-PID
        out.write(0x00) // TP-DCS: GSM 7-bit
        out.write(byteArrayOf(0x62, 0x10, 0x10, 0x21, 0x43, 0x65, 0x00)) // timestamp 2026-01-01 12:34:56
        out.write(body.length)
        out.write(pack7Bit(body))
        return out.toByteArray()
    }

    fun deliverIntent(vararg pdus: ByteArray, subscriptionId: Int = 1): Intent =
        Intent(Telephony.Sms.Intents.SMS_DELIVER_ACTION).apply {
            putExtra("pdus", pdus.map { it as Any }.toTypedArray())
            putExtra("format", "3gpp")
            putExtra("subscription", subscriptionId)
        }

    private fun pack7Bit(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (ch in text) {
            val septet = gsmSeptet(ch)
            buffer = buffer or (septet shl bits)
            bits += 7
            while (bits >= 8) {
                out.write(buffer and 0xFF)
                buffer = buffer ushr 8
                bits -= 8
            }
        }
        if (bits > 0) out.write(buffer and 0xFF)
        return out.toByteArray()
    }

    /** Letters, digits, space and simple punctuation map to themselves in the GSM basic alphabet. */
    private fun gsmSeptet(ch: Char): Int {
        require(ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch in " .,:!?'-()+*/") { "not GSM-safe: $ch" }
        return ch.code
    }
}

/** Shared uMessage engine wiring for receive-path tests. */
object SpamTestWiring {

    /** Points [SpamDatabase.getInstance] at a fresh in-memory database for this test. */
    fun freshSpamDatabase(context: Context): SpamDatabase {
        val db = Room.inMemoryDatabaseBuilder(context, SpamDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val field = SpamDatabase::class.java.getDeclaredField("instance")
        field.isAccessible = true
        field.set(null, db)
        return db
    }

    fun clearSpamDatabase() {
        val field = SpamDatabase::class.java.getDeclaredField("instance")
        field.isAccessible = true
        (field.get(null) as? SpamDatabase)?.close()
        field.set(null, null)
    }

    /** Installs [decide] as the spam filter (the repository stays uninstalled unless a test sets it). */
    fun installFilter(decide: (IncomingMessage) -> FilterDecision) {
        val filter = object : SpamFilter {
            override fun evaluate(message: IncomingMessage) = decide(message)
        }
        UMessage.filterFactory = { filter }
    }

    /** Forgets the in-process leases of [PendingSmsQueue]; row ids restart at 1 in every fresh database. */
    fun resetPendingLeases() {
        val field = PendingSmsQueue::class.java.getDeclaredField("leases")
        field.isAccessible = true
        (field.get(PendingSmsQueue) as MutableMap<*, *>).clear()
    }

    fun uninstall() {
        UMessage.filterFactory = null
        UMessage.repositoryFactory = null
    }
}

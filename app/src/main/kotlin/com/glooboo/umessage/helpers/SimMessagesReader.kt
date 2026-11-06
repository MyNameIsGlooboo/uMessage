package com.glooboo.umessage.helpers

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.Telephony.Sms

/** One text stored on a SIM card. [sent] is true for a text the user sent, which the SIM keeps as well. */
data class SimMessage(val address: String, val body: String, val date: Long, val sent: Boolean)

sealed interface SimReadResult {
    /** At least one SIM answered. The list is empty when the card holds no texts. */
    data class Messages(val messages: List<SimMessage>) : SimReadResult

    /** The phone gives apps no access to the SIM texts, or every SIM refused. */
    data object Unavailable : SimReadResult
}

/**
 * I62: reads the texts stored on the SIM card(s) through the system's `content://sms/icc` provider. It is undocumented
 * and many phones block or empty it, so a refusal is a normal answer ([SimReadResult.Unavailable]), never a crash.
 * Reading changes nothing on the phone.
 */
class SimMessagesReader(
    private val resolver: ContentResolver,
    private val subscriptionIds: () -> List<Int> = { emptyList() },
) {
    fun read(): SimReadResult {
        val perSim = subscriptionIds().mapNotNull { query(Uri.parse("$ICC_SUB/$it")) }
        // The plain list covers phones that do not answer per SIM.
        val lists = perSim.ifEmpty { listOfNotNull(query(Uri.parse(ICC))) }
        if (lists.isEmpty()) return SimReadResult.Unavailable
        return SimReadResult.Messages(lists.flatten().distinct().sortedBy { it.date })
    }

    private fun query(uri: Uri): List<SimMessage>? = try {
        resolver.query(uri, null, null, null, null)?.use(::rows)
    } catch (_: SecurityException) {
        null
    } catch (_: RuntimeException) {
        null
    }

    private fun rows(cursor: Cursor): List<SimMessage> {
        val address = cursor.getColumnIndex(Sms.ADDRESS)
        val body = cursor.getColumnIndex(Sms.BODY)
        val date = cursor.getColumnIndex(Sms.DATE)
        val status = cursor.getColumnIndex(Sms.STATUS)
        if (address < 0 || body < 0) return emptyList()
        return buildList {
            while (cursor.moveToNext()) {
                val from = cursor.getString(address)?.trim().orEmpty()
                val text = cursor.getString(body).orEmpty()
                if (from.isEmpty() || text.isEmpty()) continue
                val time = if (date >= 0 && !cursor.isNull(date)) cursor.getLong(date) else 0L
                val state = if (status >= 0 && !cursor.isNull(status)) cursor.getInt(status) else STATUS_READ
                add(SimMessage(from, text, time, state == STATUS_SENT || state == STATUS_UNSENT))
            }
        }
    }

    companion object {
        private const val ICC = "content://sms/icc"
        private const val ICC_SUB = "content://sms/icc_subId"

        /** The SIM's own message states (Android's `Sms.STATUS_ON_ICC_*`). */
        private const val STATUS_READ = 1
        private const val STATUS_SENT = 5
        private const val STATUS_UNSENT = 7
    }
}

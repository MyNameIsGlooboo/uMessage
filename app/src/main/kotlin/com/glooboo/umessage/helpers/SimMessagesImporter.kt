package com.glooboo.umessage.helpers

import android.provider.Telephony.Sms
import com.glooboo.umessage.models.SmsBackup
import com.glooboo.umessage.models.WriteOutcome
import com.glooboo.umessage.models.WriteResult

/** What an import of SIM texts did, one count per text. */
data class SimImportSummary(val added: Int, val duplicates: Int, val failed: Int)

/**
 * I62: copies SIM texts into the phone's conversations with their original dates. [write] is
 * [MessagesWriter.writeSmsMessage], which puts each text in its sender's conversation and skips one the conversation
 * already has (same time, direction and text), so importing twice adds nothing the second time. Nothing is deleted
 * from the SIM and nothing is sent.
 */
class SimMessagesImporter(private val write: (SmsBackup) -> WriteResult) {
    fun import(messages: List<SimMessage>): SimImportSummary {
        var added = 0
        var duplicates = 0
        var failed = 0
        for (message in messages) {
            when (write(message.toSms()).outcome) {
                WriteOutcome.ADDED, WriteOutcome.REPAIRED -> added++
                WriteOutcome.DUPLICATE -> duplicates++
                else -> failed++
            }
        }
        return SimImportSummary(added, duplicates, failed)
    }

    private fun SimMessage.toSms() = SmsBackup(
        subscriptionId = -1,
        address = address,
        body = body,
        date = date,
        dateSent = date,
        locked = 0,
        protocol = null,
        read = 1,
        status = -1,
        type = if (sent) Sms.MESSAGE_TYPE_SENT else Sms.MESSAGE_TYPE_INBOX,
        serviceCenter = null,
    )
}

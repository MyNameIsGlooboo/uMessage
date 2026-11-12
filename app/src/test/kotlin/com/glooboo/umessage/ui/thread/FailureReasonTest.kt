package com.glooboo.umessage.ui.thread

import android.provider.Telephony.Sms
import android.telephony.SmsManager
import com.glooboo.umessage.R
import com.glooboo.umessage.messaging.smsSendingErrorRes
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.models.ThreadItem
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Thread-27: the failed row carries the phone's stored reason, for texts only. */
class FailureReasonTest {

    private fun failed(id: Long, isMms: Boolean = false) = Message(
        id = id, body = "hi", type = Sms.MESSAGE_TYPE_FAILED, status = Sms.STATUS_NONE, participants = ArrayList(),
        date = 100, read = true, threadId = 1, isMMS = isMms, attachment = null, senderPhoneNumber = "",
        senderName = "", senderPhotoUri = "", subscriptionId = -1,
    )

    private fun errors(vararg messages: Message, codes: Map<Long, Int>) =
        buildThreadItems(messages.toList(), emptyMap(), seen = false, failureCodes = codes)
            .items.filterIsInstance<ThreadItem.ThreadError>()

    @Test
    fun aFailedTextCarriesItsStoredCode() {
        val error = errors(failed(1), codes = mapOf(1L to SmsManager.RESULT_ERROR_NO_SERVICE)).single()
        assertThat(error.reasonCode).isEqualTo(SmsManager.RESULT_ERROR_NO_SERVICE)
    }

    @Test
    fun aFailedTextWithoutAStoredCodeHasNone() {
        assertThat(errors(failed(1), codes = emptyMap()).single().reasonCode).isEqualTo(0)
    }

    @Test
    fun aPictureMessageNeverTakesATextsCode() {
        // Ids of texts and picture messages can be equal; the code belongs to the text only.
        assertThat(errors(failed(1, isMms = true), codes = mapOf(1L to 4)).single().reasonCode).isEqualTo(0)
    }

    @Test
    fun theReasonRowsFollowTheItems() {
        val items = buildThreadItems(listOf(failed(1)), emptyMap(), seen = false, failureCodes = mapOf(1L to 2)).items
        val row = buildThreadRows(items, isGroup = false).filterIsInstance<ThreadRow.Failed>().single()
        assertThat(row.reasonCode).isEqualTo(2)
    }

    @Test
    fun theCommonReasonsHaveTheirOwnWords() {
        assertThat(smsSendingErrorRes(SmsManager.RESULT_ERROR_RADIO_OFF)).isEqualTo(R.string.error_radio_turned_off)
        assertThat(smsSendingErrorRes(SmsManager.RESULT_ERROR_NO_SERVICE))
            .isEqualTo(R.string.error_service_is_unavailable)
    }

    @Test
    fun aGeneralFailureNamesNothingSpecific() {
        assertThat(smsSendingErrorRes(SmsManager.RESULT_ERROR_GENERIC_FAILURE))
            .isEqualTo(R.string.message_not_sent_short)
    }
}

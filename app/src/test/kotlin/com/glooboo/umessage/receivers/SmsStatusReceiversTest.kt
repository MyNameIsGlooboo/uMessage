package com.glooboo.umessage.receivers

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony.Sms
import android.telephony.SmsManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.messaging.SentParts
import com.glooboo.umessage.testing.FakeSmsMmsProvider
import com.glooboo.umessage.testing.offMainThread
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SmsStatusReceiversTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var provider: FakeSmsMmsProvider

    @Before
    fun setUp() {
        provider = FakeSmsMmsProvider.installSms()
    }

    @After
    fun tearDown() = resetSharedMessagesDatabase()

    private fun insertOutboxRow(): Uri = context.contentResolver.insert(Sms.CONTENT_URI, ContentValues().apply {
        put(Sms.ADDRESS, "111")
        put(Sms.BODY, "long Persian text")
        put(Sms.DATE, System.currentTimeMillis())
        put(Sms.TYPE, Sms.MESSAGE_TYPE_OUTBOX)
    })!!

    private fun sentIntent(uri: Uri, partCount: Int) =
        Intent(SendStatusReceiver.SMS_SENT_ACTION, uri).putExtra(SendStatusReceiver.EXTRA_PART_COUNT, partCount)

    /** Delivers one part's sent report the way onReceive does, minus the thread hop. */
    private fun report(uri: Uri, partCount: Int, resultCode: Int) = offMainThread {
        SmsStatusSentReceiver().handle(context, sentIntent(uri, partCount), resultCode)
    }

    private fun typeOf(uri: Uri) = provider.intColumn("sms", uri.lastPathSegment!!.toLong(), Sms.TYPE)

    @Test
    fun laterSentPartDoesNotHideAnEarlierFailedPart() {
        // RM-04 / T6: three-part message, part 1 failed, parts 2 and 3 sent.
        val uri = insertOutboxRow()
        SentParts.start(context, uri)

        report(uri, 3, SmsManager.RESULT_ERROR_GENERIC_FAILURE)
        report(uri, 3, Activity.RESULT_OK)
        report(uri, 3, Activity.RESULT_OK)

        assertThat(typeOf(uri)).isEqualTo(Sms.MESSAGE_TYPE_FAILED)
    }

    @Test
    fun messageStaysSendingUntilItsLastPartIsSent() {
        val uri = insertOutboxRow()
        SentParts.start(context, uri)

        report(uri, 2, Activity.RESULT_OK)
        assertThat(typeOf(uri)).isEqualTo(Sms.MESSAGE_TYPE_OUTBOX)

        report(uri, 2, Activity.RESULT_OK)
        assertThat(typeOf(uri)).isEqualTo(Sms.MESSAGE_TYPE_SENT)
    }

    @Test
    fun singlePartReportStillMarksSentAndFailed() {
        val ok = insertOutboxRow()
        val bad = insertOutboxRow()

        report(ok, 1, Activity.RESULT_OK)
        report(bad, 1, SmsManager.RESULT_ERROR_NO_SERVICE)

        assertThat(typeOf(ok)).isEqualTo(Sms.MESSAGE_TYPE_SENT)
        assertThat(typeOf(bad)).isEqualTo(Sms.MESSAGE_TYPE_FAILED)
    }

    @Test
    fun sentReportWithAMalformedUriDoesNotThrow() {
        // RM-22: lastPathSegment.toLong() threw NumberFormatException on the bare worker thread, killing the app.
        val intent = sentIntent(Uri.parse("content://sms/not-a-number"), 1)
        offMainThread {
            val receiver = SmsStatusSentReceiver()
            receiver.updateAndroidDatabase(context, intent, Activity.RESULT_OK)
            receiver.updateAppDatabase(context, intent, Activity.RESULT_OK)
        }
    }

    @Test
    fun deliveryReportWithoutPduOrWithAMalformedUriDoesNotThrow() {
        // RM-22: a delivery report with no pdu and a uri that does not end in a number.
        val intent = Intent(SendStatusReceiver.SMS_DELIVERED_ACTION, Uri.parse("content://sms/oops"))
        offMainThread {
            val receiver = SmsStatusDeliveredReceiver()
            receiver.updateAndroidDatabase(context, intent, Activity.RESULT_OK)
            receiver.updateAppDatabase(context, intent, Activity.RESULT_OK)
        }
    }

    @Test
    fun handleSwallowsWhateverAnUpdateThrows() {
        val throwing = object : SendStatusReceiver() {
            override fun updateAndroidDatabase(context: Context, intent: Intent, receiverResultCode: Int) {
                error("broken broadcast")
            }

            override fun updateAppDatabase(context: Context, intent: Intent, receiverResultCode: Int) = Unit
        }

        throwing.handle(context, Intent(), Activity.RESULT_OK) // must not throw
    }
}

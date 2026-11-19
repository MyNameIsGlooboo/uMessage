package com.glooboo.umessage.messaging

import android.app.Application
import android.provider.Telephony
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.extensions.config
import com.glooboo.umessage.testing.FakeMessageStore
import com.glooboo.umessage.testing.ShadowRecordingSmsManager
import com.glooboo.umessage.testing.joinAppBackgroundThreads
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The choice [sendMessageDetailed] makes before anything is sent: duplicate recipients are dropped, and the message
 * goes out as SMS, as a group MMS or as a long-text MMS. Fake numbers only.
 */
@RunWith(AndroidJUnit4::class)
class SendDispatchTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        ShadowRecordingSmsManager.reset()
        FakeMessageStore.install()
        app.config.sendGroupMessageMMS = false
        app.config.sendLongAsMmsAfterParts = LongTextMms.NEVER
    }

    @After
    fun tearDown() {
        joinAppBackgroundThreads()
        resetSharedMessagesDatabase()
    }

    private fun send(text: String, vararg addresses: String) =
        app.sendMessageDetailed(text, addresses.toList(), subId = null, attachments = emptyList())

    private fun smsRows(): Int =
        app.contentResolver.query(Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms._ID), null, null, null)
            .use { it?.count ?: 0 }

    private fun destinations() = ShadowRecordingSmsManager.textSends.map { it.destination }

    @Test
    fun distinctRecipients_dropsTheSameNumberWrittenTwice() {
        assertThat(distinctRecipients(listOf("+15555550100", "+15555550100"))).containsExactly("+15555550100")
    }

    @Test
    fun distinctRecipients_keepsDifferentNumbersInOrder() {
        assertThat(distinctRecipients(listOf("+15555550101", "+15555550100", "+15555550102")))
            .containsExactly("+15555550101", "+15555550100", "+15555550102").inOrder()
    }

    @Test
    fun distinctRecipients_dropsTheSameNumberWrittenAnotherWay() {
        assertThat(distinctRecipients(listOf("+15555550100", "5555550100"))).containsExactly("+15555550100")
    }

    @Test
    fun distinctRecipients_ofNothingIsNothing() {
        assertThat(distinctRecipients(emptyList())).isEmpty()
    }

    @Test
    fun aShortTextToOnePersonIsOneSms() {
        val outcome = send("Hello", "+15555550100")

        assertThat(outcome.handedOff).isTrue()
        assertThat(destinations()).containsExactly("+15555550100")
    }

    @Test
    fun theSameNumberTwiceIsSentToOnceNotTwice() {
        send("Hello", "+15555550100", "+15555550100")

        assertThat(destinations()).containsExactly("+15555550100")
    }

    @Test
    fun aGroupWithGroupMmsOffGoesOutAsOneSmsEach() {
        app.config.sendGroupMessageMMS = false

        send("Hello", "+15555550100", "+15555550101")

        assertThat(destinations()).containsExactly("+15555550100", "+15555550101")
    }

    @Test
    fun aGroupWithGroupMmsOnIsNotSentAsSms() {
        app.config.sendGroupMessageMMS = true

        send("Hello", "+15555550100", "+15555550101")

        assertThat(ShadowRecordingSmsManager.textSends).isEmpty()
        assertThat(smsRows()).isEqualTo(0)
    }

    @Test
    fun aGroupThatIsReallyOnePersonStaysAnSmsEvenWithGroupMmsOn() {
        app.config.sendGroupMessageMMS = true

        send("Hello", "+15555550100", "+15555550100")

        assertThat(destinations()).containsExactly("+15555550100")
    }

    @Test
    fun aLongTextStaysSmsWhenTheUserNeverChoseMms() {
        send("a".repeat(2_000), "+15555550100")

        assertThat(ShadowRecordingSmsManager.textSends).hasSize(1)
    }

    @Test
    fun aLongTextBecomesAnMmsFromTheChosenNumberOfParts() {
        app.config.sendLongAsMmsAfterParts = 2

        send("a".repeat(161), "+15555550100")

        assertThat(ShadowRecordingSmsManager.textSends).isEmpty()
        assertThat(smsRows()).isEqualTo(0)
    }

    @Test
    fun aTextJustUnderTheChosenNumberOfPartsStaysSms() {
        app.config.sendLongAsMmsAfterParts = 2

        send("a".repeat(160), "+15555550100")

        assertThat(destinations()).containsExactly("+15555550100")
    }
}

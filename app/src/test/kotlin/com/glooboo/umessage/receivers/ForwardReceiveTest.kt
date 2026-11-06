package com.glooboo.umessage.receivers

import android.app.NotificationManager
import android.content.Context
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.extensions.config
import com.glooboo.umessage.helpers.W3aPrefs
import com.glooboo.umessage.spam.ForwardStatus
import com.glooboo.umessage.testing.offMainThread
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.FilterDecision
import com.glooboo.umessage.spam.ForwardRule
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.UMessage
import com.glooboo.umessage.spam.data.ForwardRuleEntity
import com.glooboo.umessage.spam.data.IncomingSmsProcessor
import com.glooboo.umessage.spam.data.PendingSmsEntity
import com.glooboo.umessage.spam.data.SpamDatabase
import com.glooboo.umessage.testing.FakeTelephonyProvider
import com.glooboo.umessage.testing.ShadowRecordingSmsManager
import com.glooboo.umessage.testing.SmsPdus
import com.glooboo.umessage.testing.SpamTestWiring
import com.glooboo.umessage.testing.joinAppBackgroundThreads
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.glooboo.umessage.ui.spam.FakeSpamRepository
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLooper

/**
 * Forwarding end to end: an SMS arrives, passes the filter and lands in the inbox, then the forward goes out through
 * the app's SMS path to the recording SmsManager. Blocked spam is never forwarded.
 */
@RunWith(AndroidJUnit4::class)
class ForwardReceiveTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var telephony: FakeTelephonyProvider
    private lateinit var spamDb: SpamDatabase

    @Before
    fun setUp() {
        telephony = FakeTelephonyProvider.install()
        spamDb = SpamTestWiring.freshSpamDatabase(context)
        SpamTestWiring.resetPendingLeases()
        UMessage.log(context).clear()
        ShadowRecordingSmsManager.reset()
        SpamTestWiring.installFilter { FilterDecision.Allow(DecisionReason.NO_MATCH) }
    }

    @After
    fun tearDown() {
        // Forwards refresh the UI through EventBus and app threads: let them finish before the databases go away.
        joinAppBackgroundThreads()
        SpamTestWiring.uninstall()
        SpamTestWiring.clearSpamDatabase()
        ShadowRecordingSmsManager.reset()
        resetSharedMessagesDatabase()
    }

    private fun addRule(rule: ForwardRule) = spamDb.forwardDao().insert(ForwardRuleEntity.from(rule))

    private fun bankRule(targets: List<String> = listOf("+15550001111"), includeCodes: Boolean = false) = ForwardRule(
        senderType = RuleType.NUMBER, senderPattern = "+15551234567", targets = targets, includeCodes = includeCodes,
    )

    private fun receive(sender: String, body: String) {
        SmsReceiver().handle(context, SmsPdus.deliverIntent(SmsPdus.deliverPdu(sender, body)))
    }

    private fun sentTexts() = ShadowRecordingSmsManager.textSends.map { it.destination to it.parts.joinToString("") }
    private fun log() = UMessage.log(context).entries().filter { it.source == "FORWARD" }

    @Test
    fun matchingInboxSms_isForwardedAsMultipartSmsAndLogged() {
        addRule(bankRule())

        receive("+15551234567", "Deposit of 500 to your account")

        assertThat(sentTexts()).containsExactly("+15550001111" to "[from +15551234567] Deposit of 500 to your account")
        // Long text: split into parts by the (test) SmsManager, sent as one multipart SMS, never as MMS.
        assertThat(ShadowRecordingSmsManager.textSends.single().parts.size).isGreaterThan(1)
        assertThat(ShadowRecordingSmsManager.multimediaSends).isEmpty()
        // The original is in the inbox and the forward is saved in the target's thread.
        val rows = telephony.smsRows()
        assertThat(rows.filter { it["type"] == Telephony.Sms.MESSAGE_TYPE_INBOX.toLong() }.map { it["address"] })
            .containsExactly("+15551234567")
        assertThat(rows.filter { it["type"] != Telephony.Sms.MESSAGE_TYPE_INBOX.toLong() }.map { it["address"] })
            .containsExactly("+15550001111")
        val entry = log().single()
        assertThat(entry.decision).isEqualTo("FORWARD")
        assertThat(entry.reason).contains("+15550001111")
        assertThat(entry.reason).endsWith("SENT")
        assertThat(entry.reason).doesNotContain("Deposit")
    }

    @Test
    fun otherSender_isNotForwarded() {
        addRule(bankRule())

        receive("+15559999999", "Deposit of 500")

        assertThat(sentTexts()).isEmpty()
        assertThat(log()).isEmpty()
    }

    @Test
    fun blockedSpam_isNeverForwarded() {
        addRule(bankRule())
        UMessage.repositoryFactory = { FakeSpamRepository() }
        SpamTestWiring.installFilter { FilterDecision.Block(DecisionReason.BLOCK_RULE) }

        receive("+15551234567", "Deposit of 500")

        assertThat(telephony.smsRows()).isEmpty()
        assertThat(sentTexts()).isEmpty()
    }

    @Test
    fun verificationCode_isSkippedAndLogged() {
        addRule(bankRule())

        receive("+15551234567", "Your verification code is 482913")

        assertThat(sentTexts()).isEmpty()
        assertThat(log().single().decision).isEqualTo("FORWARD_SKIPPED")
        assertThat(log().single().reason).contains("CODE_EXCLUDED")
    }

    @Test
    fun verificationCode_isForwardedWhenTheRuleIncludesCodes() {
        addRule(bankRule(includeCodes = true))

        receive("+15551234567", "Your verification code is 482913")

        assertThat(sentTexts().map { it.first }).containsExactly("+15550001111")
    }

    @Test
    fun aForwardThatArrives_isNotForwardedAgain() {
        addRule(bankRule())

        // "[" is not in the GSM basic alphabet the PDU helper writes, so this one goes straight to the processor.
        val now = System.currentTimeMillis()
        IncomingSmsProcessor.process(
            context,
            PendingSmsEntity(
                address = "+15551234567", subject = "", body = "[from Bank] Deposit of 500", date = now,
                subscriptionId = 1, status = -1, createdAt = now,
            ),
        )

        assertThat(sentTexts()).isEmpty()
        assertThat(log().single().reason).contains("LOOP_FORWARD")
    }

    @Test
    fun refusedSend_isLoggedAndNotifiedAndTheOtherTargetsStillGetIt() {
        addRule(bankRule(targets = listOf("+15550001111", "+15550002222")))
        ShadowRecordingSmsManager.failingDestinations.add("+15550001111")

        receive("+15551234567", "Deposit of 500")
        ShadowLooper.idleMainLooper()

        assertThat(sentTexts().map { it.first }).containsExactly("+15550002222")
        assertThat(log().map { it.decision }).containsExactly("FORWARD_FAILED", "FORWARD")
        assertThat(log().first { it.decision == "FORWARD_FAILED" }.reason).contains("+15550001111")
        val notifications = shadowOf(context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .allNotifications
        assertThat(notifications).isNotEmpty()
        // The original message is still delivered once and the pending row is gone. (The fake provider applies a
        // by-URI status update to every row, so the row is found by its sender, not its type.)
        assertThat(telephony.smsRows().count { it["address"] == "+15551234567" }).isEqualTo(1)
        assertThat(spamDb.spamDao().getPending()).isEmpty()
    }

    /** Several targets are separate SMS, never one group MMS, even with "send group messages as MMS" on. */
    @Test
    fun severalTargets_eachGetTheirOwnSmsEvenWithGroupMmsOn() {
        context.config.sendGroupMessageMMS = true
        try {
            addRule(bankRule(targets = listOf("+15550001111", "+15550002222", "+15550003333")))

            receive("+15551234567", "Deposit of 500")

            assertThat(sentTexts().map { it.first })
                .containsExactly("+15550001111", "+15550002222", "+15550003333").inOrder()
            assertThat(ShadowRecordingSmsManager.multimediaSends).isEmpty()
            // One saved row per target, no group summary row joining them.
            val outgoing = telephony.smsRows().map { it["address"] as String }.filter { it != "+15551234567" }
            assertThat(outgoing).containsExactly("+15550001111", "+15550002222", "+15550003333")
        } finally {
            context.config.sendGroupMessageMMS = false
        }
    }

    private fun logRows() = spamDb.forwardLogDao().recent(0).sortedBy { it.id }

    /**
     * H2: two forwards to the same number, the first fails at the radio. Exactly that row becomes FAILED, found by the
     * log id its sent report carries, and the second (delivered) forward stays SENT.
     */
    @Test
    fun radioFailure_marksExactlyTheForwardItBelongsTo() {
        addRule(bankRule())
        receive("+15551234567", "Deposit of 500")
        receive("+15551234567", "Deposit of 700")
        assertThat(sentTexts()).hasSize(2)
        val (first, second) = logRows()
        assertThat(first.status).isEqualTo(ForwardStatus.SENT)

        val report = shadowOf(ShadowRecordingSmsManager.textSends.first().sentIntents.first()).savedIntent
        assertThat(report.getLongExtra(SmsStatusSentReceiver.EXTRA_FORWARD_LOG_ID, -1)).isEqualTo(first.id)
        offMainThread { SmsStatusSentReceiver().handle(context, report, SmsManager.RESULT_ERROR_GENERIC_FAILURE) }

        assertThat(spamDb.forwardLogDao().get(first.id)!!.status).isEqualTo(ForwardStatus.FAILED)
        assertThat(spamDb.forwardLogDao().get(second.id)!!.status).isEqualTo(ForwardStatus.SENT)
    }

    /** Bug 9 and M4: a forward the daily limit stops is not sent, and the Forwarded tab lists it as skipped. */
    @Test
    fun dailyLimit_skipsTheRestAndLogsThemAsSkipped() {
        val prefs = W3aPrefs.get(context)
        prefs.dailyForwardLimit = 1
        try {
            addRule(bankRule())
            receive("+15551234567", "Deposit of 500")
            receive("+15551234567", "Deposit of 700")

            assertThat(sentTexts()).hasSize(1)
            val rows = logRows()
            assertThat(rows.map { it.status }).containsExactly(ForwardStatus.SENT, ForwardStatus.SKIPPED).inOrder()
            assertThat(rows.last().errorCode).isEqualTo("DAILY_LIMIT")
            assertThat(log().map { it.reason }.any { it.endsWith("DAILY_LIMIT") }).isTrue()
        } finally {
            prefs.dailyForwardLimit = 0
            prefs.dailyLimitNotifiedDay = -1
        }
    }

    @Test
    fun disabledRule_sendsNothing() {
        addRule(bankRule().copy(enabled = false))

        receive("+15551234567", "Deposit of 500")

        assertThat(sentTexts()).isEmpty()
    }
}

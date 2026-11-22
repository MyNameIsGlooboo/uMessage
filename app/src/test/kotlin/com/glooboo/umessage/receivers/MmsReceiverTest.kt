package com.glooboo.umessage.receivers

import android.content.Context
import android.content.Intent
import android.telephony.SubscriptionManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.FilterDecision
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.spam.IncomingMessage
import com.glooboo.umessage.spam.UMessage
import com.glooboo.umessage.spam.data.SpamDatabase
import com.glooboo.umessage.spam.engine.Judgement
import com.glooboo.umessage.spam.engine.SenderVerdict
import com.glooboo.umessage.spam.mms.IncomingMms
import com.glooboo.umessage.spam.mms.MmsSenderCheck
import com.glooboo.umessage.testing.FakeMmsSpamStore
import com.glooboo.umessage.testing.FakeTelephonyProvider
import com.glooboo.umessage.testing.ShadowRecordingSmsManager
import com.glooboo.umessage.testing.SpamTestWiring
import com.glooboo.umessage.testing.WatchedBroadcast
import com.glooboo.umessage.testing.joinAppBackgroundThreads
import com.google.common.truth.Truth.assertThat
import com.klinker.android.send_message.MmsReceivedReceiver
import org.fossify.commons.models.PhoneNumber
import org.fossify.commons.models.SimpleContact
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A downloaded MMS ([MmsReceiver] and its mmslib hooks): the content hook never blocks, the SIM it came in on is the
 * one remembered at the push (never the default SIM), and nothing here may crash or hold the broadcast. Robolectric
 * has no MMS provider, so the "announce and forward" step is covered only where it can run without one.
 */
@RunWith(AndroidJUnit4::class)
class MmsReceiverTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var spamDb: SpamDatabase

    @Before
    fun setUp() {
        FakeTelephonyProvider.install()
        spamDb = SpamTestWiring.freshSpamDatabase(context)
        UMessage.log(context).clear()
        ShadowRecordingSmsManager.reset()
    }

    @After
    fun tearDown() {
        joinAppBackgroundThreads()
        SpamTestWiring.uninstall()
        SpamTestWiring.clearSpamDatabase()
        ShadowRecordingSmsManager.reset()
    }

    /** The sender checks of a placed MMS: they record the message they are made for and keep it in Messages. */
    private class AllowCheck : MmsSenderCheck {
        override fun probe(): FilterDecision = FilterDecision.Allow(DecisionReason.NO_MATCH)
        override fun judge(): Judgement = Judgement(SenderVerdict.Messages(DecisionReason.NO_MATCH))
        override fun historyChecked(): Boolean = true
        override fun log(decision: String, reason: String, ruleId: Long?) = Unit
    }

    private fun mms() = Message(
        id = 7L, body = "photo", type = 1, status = -1,
        participants = arrayListOf(
            SimpleContact(1, 1, SENDER, "", arrayListOf(PhoneNumber(SENDER, 0, "", SENDER)), ArrayList(), ArrayList()),
        ),
        date = 1_700_000_000, read = false, threadId = 12L, isMMS = true, attachment = null,
        senderPhoneNumber = SENDER, senderName = "", senderPhotoUri = "", subscriptionId = 1,
    )

    /**
     * Runs the hooks mmslib calls for one downloaded MMS, then its placement; returns what the sender checks were made
     * for and whether the content hook blocked.
     */
    private fun checksSee(address: String, text: String): Pair<IncomingMessage?, Boolean> {
        var seen: IncomingMessage? = null
        val handler = MmsDownloadHandler().apply {
            incomingMms = { IncomingMms(FakeMmsSpamStore()) }
            senderCheck = { _: Context, message: IncomingMessage, _: Long ->
                seen = message
                AllowCheck()
            }
            announce = { _, _, _, _ -> }
            forwardOf = { _, _ -> }
            updateChatOf = { _, _, _ -> }
        }
        handler.isAddressBlocked(context, address)
        val blocked = handler.isContentBlocked(context, text)
        handler.placeAndAnnounce(context, mms(), SENDER)
        return seen to blocked
    }

    @Test
    fun theChecksGetTheSenderWithoutTheTypeSuffixAndTheMmsFlag() {
        val (seen, blocked) = checksSee("$SENDER/TYPE=PLMN", "hello")

        assertThat(blocked).isFalse()
        assertThat(seen!!.address).isEqualTo(SENDER)
        assertThat(seen.body).isEqualTo("hello")
        assertThat(seen.isMms).isTrue()
    }

    @Test
    fun theSimIsTheOneRememberedAtThePush() {
        MmsSubscriptions.remember(SENDER, 2)

        val (seen, _) = checksSee("$SENDER/TYPE=PLMN", "hi")

        assertThat(seen!!.subscriptionId).isEqualTo(2)
    }

    @Test
    fun aSenderNeverSeenAtThePush_hasNoSimNotTheDefaultOne() {
        val (seen, _) = checksSee("+15550009876", "hi")

        assertThat(seen!!.subscriptionId).isEqualTo(SubscriptionManager.INVALID_SUBSCRIPTION_ID)
    }

    @Test
    fun theContentHookAlwaysLetsTheLibraryStoreTheMms() {
        var asked = 0
        SpamTestWiring.installFilter { asked++; FilterDecision.Block(DecisionReason.BLOCK_RULE, 3L) }
        val handler = MmsDownloadHandler()

        handler.isAddressBlocked(context, "$SENDER/TYPE=PLMN")
        val blocked = handler.isContentBlocked(context, "spam")

        // 6.2: never "blocked" (the library would delete the notice); the MMS is placed once it is stored.
        assertThat(blocked).isFalse()
        assertThat(asked).isEqualTo(0)
        assertThat(spamDb.spamDao().getAllBlocked()).isEmpty()
    }

    @Test
    fun anMmsThatIsNotInTheProvider_isNotAnnouncedOrForwardedAndDoesNotThrow() {
        MmsDownloadHandler().onMessageReceived(context, null)

        assertThat(ShadowRecordingSmsManager.textSends).isEmpty()
    }

    @Test
    fun onReceive_alwaysFinishesTheBroadcastEvenWhenTheDownloadFailed() {
        val receiver = MmsReceiver()
        val broadcast = WatchedBroadcast.attachTo(receiver)
        val result = Intent(context, MmsReceiver::class.java)
            .putExtra(MmsReceivedReceiver.EXTRA_FILE_PATH, File(context.cacheDir, "download.missing.dat").path)
            .putExtra(MmsReceivedReceiver.EXTRA_LOCATION_URL, "http://mmsc.example.com/msg/none")
            .putExtra(MmsReceivedReceiver.EXTRA_TRANSACTION_ID, "T-1")
            .putExtra(MmsReceivedReceiver.SUBSCRIPTION_ID, 1)

        receiver.onReceive(context, result)

        assertThat(broadcast.awaitFinished()).isTrue()
    }

    private companion object {
        const val SENDER = "+15550001234"
    }
}

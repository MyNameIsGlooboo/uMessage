package com.glooboo.umessage.receivers

import android.content.Context
import android.content.Intent
import android.telephony.SubscriptionManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.helpers.ProviderTruthFlag
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.FilterDecision
import com.glooboo.umessage.spam.IncomingMessage
import com.glooboo.umessage.spam.UMessage
import com.glooboo.umessage.spam.data.SpamDatabase
import com.glooboo.umessage.testing.FakeTelephonyProvider
import com.glooboo.umessage.testing.ProviderTruthFlagRule
import com.glooboo.umessage.testing.ShadowRecordingSmsManager
import com.glooboo.umessage.testing.SpamTestWiring
import com.glooboo.umessage.testing.WatchedBroadcast
import com.glooboo.umessage.testing.joinAppBackgroundThreads
import com.google.common.truth.Truth.assertThat
import com.klinker.android.send_message.MmsReceivedReceiver
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A downloaded MMS ([MmsReceiver] and its mmslib hooks): the content filter fails open, the SIM it came in on is the
 * one remembered at the push (never the default SIM), and nothing here may crash or hold the broadcast. Robolectric
 * has no MMS provider, so the "announce and forward" step is covered only where it can run without one.
 */
@RunWith(AndroidJUnit4::class)
class MmsReceiverTest {

    @get:Rule val flag = ProviderTruthFlagRule(enabled = false)

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

    /** Runs the hooks mmslib calls for one downloaded MMS; returns what the filter was asked and whether it blocked. */
    private fun filterSees(
        address: String,
        text: String,
        decide: (IncomingMessage) -> FilterDecision = { FilterDecision.Allow(DecisionReason.NO_MATCH) },
    ): Pair<IncomingMessage?, Boolean> {
        var seen: IncomingMessage? = null
        SpamTestWiring.installFilter { message ->
            seen = message
            decide(message)
        }
        val handler = MmsDownloadHandler()
        handler.isAddressBlocked(context, address)
        val blocked = handler.isContentBlocked(context, text)
        return seen to blocked
    }

    @Test
    fun theFilterIsAskedWithTheSenderWithoutTheTypeSuffixAndTheMmsFlag() {
        val (seen, blocked) = filterSees("+15550001234/TYPE=PLMN", "hello")

        assertThat(blocked).isFalse()
        assertThat(seen!!.address).isEqualTo("+15550001234")
        assertThat(seen.body).isEqualTo("hello")
        assertThat(seen.isMms).isTrue()
    }

    @Test
    fun theSimIsTheOneRememberedAtThePush() {
        MmsSubscriptions.remember("+15550001234", 2)

        val (seen, _) = filterSees("+15550001234/TYPE=PLMN", "hi")

        assertThat(seen!!.subscriptionId).isEqualTo(2)
    }

    @Test
    fun aSenderNeverSeenAtThePush_hasNoSimNotTheDefaultOne() {
        val (seen, _) = filterSees("+15550009876", "hi")

        assertThat(seen!!.subscriptionId).isEqualTo(SubscriptionManager.INVALID_SUBSCRIPTION_ID)
    }

    @Test
    fun aFilterThatThrows_letsTheMmsIn() {
        val (_, blocked) = filterSees("+15550001234", "hello") { error("filter broke") }

        assertThat(blocked).isFalse()
        assertThat(spamDb.spamDao().getAllBlocked()).isEmpty()
    }

    @Test
    fun aBlockThatCannotBeStored_failsOpenAndKeepsTheDownloadedFile() {
        val file = File(context.cacheDir, "download.fail-open.dat").apply { writeBytes(byteArrayOf(1)) }
        val handler = MmsDownloadHandler().apply { pduPath = file.path }
        SpamTestWiring.installFilter { FilterDecision.Block(DecisionReason.UNKNOWN_SENDER) }
        spamDb.close()

        handler.isAddressBlocked(context, "+15550001234")
        val blocked = handler.isContentBlocked(context, "spam")

        // Not kept in Blocked, so it is delivered normally: a message is never lost to a storage failure.
        assertThat(blocked).isFalse()
        assertThat(file.exists()).isTrue()
    }

    @Test
    fun withProviderTruth_theContentHookAlwaysLetsTheLibraryStoreTheMms() {
        ProviderTruthFlag.enabled = true
        val file = File(context.cacheDir, "download.provider-truth.dat").apply { writeBytes(byteArrayOf(1)) }
        var asked = 0
        SpamTestWiring.installFilter { asked++; FilterDecision.Block(DecisionReason.BLOCK_RULE, 3L) }
        val handler = MmsDownloadHandler().apply { pduPath = file.path }

        handler.isAddressBlocked(context, "+15550001234/TYPE=PLMN")
        val blocked = handler.isContentBlocked(context, "spam")

        // 6.2: never "blocked" (the library would delete the notice); the MMS is placed once it is stored.
        assertThat(blocked).isFalse()
        assertThat(asked).isEqualTo(0)
        assertThat(spamDb.spamDao().getAllBlocked()).isEmpty()
        assertThat(file.exists()).isTrue()
    }

    @Test
    fun withProviderTruth_anMmsThatIsNotInTheProvider_doesNotThrow() {
        ProviderTruthFlag.enabled = true

        MmsDownloadHandler().onMessageReceived(context, null)

        assertThat(ShadowRecordingSmsManager.textSends).isEmpty()
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
}

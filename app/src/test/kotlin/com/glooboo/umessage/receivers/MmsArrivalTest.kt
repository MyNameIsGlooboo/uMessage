package com.glooboo.umessage.receivers

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.messaging.MmsDownloadFailure
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.FilterDecision
import com.glooboo.umessage.spam.IncomingMessage
import com.glooboo.umessage.spam.engine.Judgement
import com.glooboo.umessage.spam.engine.SenderVerdict
import com.glooboo.umessage.spam.engine.SpamCause
import com.glooboo.umessage.spam.engine.ForwardCandidate
import com.glooboo.umessage.spam.mms.IncomingMms
import com.glooboo.umessage.spam.mms.MmsSenderCheck
import com.glooboo.umessage.spam.mms.NoticeRow
import com.glooboo.umessage.testing.FakeMmsProvider
import com.glooboo.umessage.testing.FakeMmsSpamStore
import com.glooboo.umessage.testing.MmsPduFixtures
import com.glooboo.umessage.testing.ProviderTruthFlagRule
import com.glooboo.umessage.testing.WatchedBroadcast
import com.glooboo.umessage.testing.joinAppBackgroundThreads
import com.google.common.truth.Truth.assertThat
import com.klinker.android.send_message.MmsReceivedReceiver
import org.fossify.commons.models.PhoneNumber
import org.fossify.commons.models.SimpleContact
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Provider truth, 6.2 (step E, E-7): what the receiver does with a placed MMS. A one-to-one Spam MMS posts nothing,
 * is not forwarded and does not unarchive its chat; a blocked member's group MMS is silent; an unknown member's group
 * MMS and a Messages MMS are announced and forwarded. The participants are counted without the user's own numbers.
 * The announce, forward and chat steps are recorded instead of run (Robolectric has no MMS provider for the library).
 */
@RunWith(AndroidJUnit4::class)
class MmsArrivalTest {

    @get:Rule val flag = ProviderTruthFlagRule(enabled = true)

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store = FakeMmsSpamStore()

    private val announced = mutableListOf<DecisionReason?>()
    private val forwarded = mutableListOf<ForwardCandidate>()
    private val updated = mutableListOf<Boolean>()
    private var judged = 0

    @After
    fun tearDown() {
        joinAppBackgroundThreads()
    }

    private class Check(val answer: FilterDecision?, val judgement: Judgement?, val onJudge: () -> Unit) :
        MmsSenderCheck {
        override fun probe(): FilterDecision? = answer
        override fun judge(): Judgement? = judgement.also { onJudge() }
        override fun historyChecked(): Boolean = true
        override fun log(decision: String, reason: String, ruleId: Long?) = Unit
    }

    private fun handler(
        probe: FilterDecision? = FilterDecision.Allow(DecisionReason.CONTACT),
        judgement: Judgement? = Judgement(SenderVerdict.Messages(DecisionReason.CONTACT)),
        own: List<String> = emptyList(),
    ) = MmsDownloadHandler().apply {
        incomingMms = { IncomingMms(store) }
        senderCheck = { _: Context, _: IncomingMessage, _: Long -> Check(probe, judgement) { judged++ } }
        ownNumbers = { own }
        announce = { _, _, _, reason -> announced += reason }
        forwardOf = { _, candidate -> forwarded += candidate }
        updateChatOf = { _, _, unarchive -> updated += unarchive }
    }

    private fun mms(thread: Long, vararg numbers: String) = Message(
        id = 7L, body = "photo", type = 1, status = -1,
        participants = ArrayList(numbers.mapIndexed { i, number ->
            val phone = arrayListOf(PhoneNumber(number, 0, "", number))
            SimpleContact(i + 1, i + 1, number, "", phone, ArrayList(), ArrayList())
        }),
        date = 1_700_000_000, read = false, threadId = thread, isMMS = true, attachment = null,
        senderPhoneNumber = SENDER, senderName = "", senderPhotoUri = "", subscriptionId = 1,
    )

    @Test
    fun aOneToOneSpamMms_postsNothingIsNotForwardedAndDoesNotUnarchive() {
        val spam = Judgement(SenderVerdict.Spam(SpamCause.UNKNOWN, DecisionReason.UNKNOWN_SENDER))

        handler(judgement = spam).placeAndAnnounce(context, mms(12L, SENDER), SENDER)

        assertThat(announced).isEmpty()
        assertThat(forwarded).isEmpty()
        assertThat(updated).containsExactly(false)
        assertThat(store.rowSilent.values).containsExactly(true)
        assertThat(store.spamRows[12L]?.second).isEqualTo(SpamCause.UNKNOWN)
    }

    @Test
    fun aGroupMmsFromABlockedMember_isSilentAndNotForwarded() {
        val handler = handler(probe = FilterDecision.Block(DecisionReason.BLOCK_RULE, 3L))

        handler.placeAndAnnounce(context, mms(40L, SENDER, OTHER), SENDER)

        assertThat(announced).isEmpty()
        assertThat(forwarded).isEmpty()
        assertThat(updated).containsExactly(false)
        assertThat(judged).isEqualTo(0)
        assertThat(store.spamRows).isEmpty()
        assertThat(store.rowSilent).isEmpty()
    }

    @Test
    fun aGroupMmsFromAnUnknownMember_isAnnouncedAndForwarded() {
        val handler = handler(probe = FilterDecision.Block(DecisionReason.UNKNOWN_SENDER))

        handler.placeAndAnnounce(context, mms(40L, SENDER, OTHER), SENDER)

        assertThat(announced).containsExactly(DecisionReason.UNKNOWN_SENDER)
        assertThat(forwarded.single().isMms).isTrue()
        assertThat(judged).isEqualTo(0)
        assertThat(store.spamRows).isEmpty()
    }

    @Test
    fun aMessagesMms_isAnnouncedWithItsReasonAndForwarded() {
        handler().placeAndAnnounce(context, mms(12L, SENDER), SENDER)

        assertThat(announced).containsExactly(DecisionReason.CONTACT)
        assertThat(forwarded).hasSize(1)
        assertThat(updated).isEmpty()
        assertThat(judged).isEqualTo(1)
    }

    @Test
    fun theUsersOwnNumberIsNotAParticipant_soTheChatIsOneToOneAndJudged() {
        val spam = Judgement(SenderVerdict.Spam(SpamCause.UNKNOWN, DecisionReason.UNKNOWN_SENDER))

        handler(judgement = spam, own = listOf(OWN)).placeAndAnnounce(context, mms(12L, SENDER, OWN), SENDER)

        assertThat(judged).isEqualTo(1)
        assertThat(announced).isEmpty()
    }

    @Test
    fun e10_aChatWhoseParticipantsCannotBeRead_isTreatedAsAGroupAndAnnounced() {
        val spam = Judgement(SenderVerdict.Spam(SpamCause.UNKNOWN, DecisionReason.UNKNOWN_SENDER))
        val handler = handler(probe = FilterDecision.Block(DecisionReason.UNKNOWN_SENDER), judgement = spam)

        handler.placeAndAnnounce(context, mms(12L), SENDER)

        assertThat(judged).isEqualTo(0)
        assertThat(announced).hasSize(1)
        assertThat(store.rowSilent).isEmpty()
    }

    @Test
    fun theEmptyChatOfTheNotice_isForgottenWhenTheMmsLandsInTheGroup_butNotMmslibsDummyThread() {
        val provider = FakeMmsProvider.install()
        val shown = handler(probe = FilterDecision.Block(DecisionReason.UNKNOWN_SENDER))
            .apply { noticeUri = provider.addNotice(SENDER, threadId = 55L) }
        shown.isAddressBlocked(context, SENDER)
        shown.placeAndAnnounce(context, mms(66L, SENDER, OTHER), SENDER)

        val dummy = handler(probe = FilterDecision.Block(DecisionReason.UNKNOWN_SENDER))
            .apply { noticeUri = provider.addNotice(SENDER, transactionId = "T-2") }
        dummy.isAddressBlocked(context, SENDER)
        dummy.placeAndAnnounce(context, mms(66L, SENDER, OTHER), SENDER)

        assertThat(store.forgotten).containsExactly(55L)
    }

    @Test
    fun e5_aResultTheLibraryCouldNotStore_runsTheFailurePath() {
        var failed = 0
        val receiver = MmsReceiver().apply {
            failure = MmsDownloadFailure(
                store = { store },
                probe = { _, _ -> failed++; null },
                notify = {},
                toast = {},
                finishLibraryDownload = {},
            )
        }
        // A good PDU the library cannot store (Robolectric has no MMS provider): neither stored nor an error.
        val pdu = MmsPduFixtures.retrieveConf(context, "hello")
        val file = File(context.cacheDir, "download.unstorable.dat").apply { writeBytes(pdu) }
        val notice = Uri.parse("content://mms/inbox/42")
        store.notices[notice] = NoticeRow(FakeMmsSpamStore.NO_CHAT, SENDER)
        val broadcast = WatchedBroadcast.attachTo(receiver)

        receiver.onReceive(
            context,
            Intent(context, MmsReceiver::class.java)
                .putExtra(MmsReceivedReceiver.EXTRA_FILE_PATH, file.path)
                .putExtra(MmsReceivedReceiver.EXTRA_LOCATION_URL, "http://mmsc.example.com/msg/unstorable")
                .putExtra(MmsReceivedReceiver.EXTRA_TRANSACTION_ID, "T-1")
                .putExtra(MmsReceivedReceiver.EXTRA_URI, notice)
                .putExtra(MmsReceivedReceiver.SUBSCRIPTION_ID, 1),
        )

        assertThat(broadcast.awaitFinished()).isTrue()
        assertThat(failed).isEqualTo(1)
        assertThat(store.rowThread[notice]).isEqualTo(store.threads.getValue(SENDER))
    }

    private companion object {
        const val SENDER = "+15550001234"
        const val OTHER = "+15550004321"
        const val OWN = "+15550009999"
    }
}

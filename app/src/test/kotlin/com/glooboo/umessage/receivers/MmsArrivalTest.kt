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
import com.glooboo.umessage.spam.mms.AndroidMmsSpamStore
import com.glooboo.umessage.spam.mms.IncomingMms
import com.glooboo.umessage.spam.mms.NoticeMark
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

    /** M-T5: a filter that throws on every call; [blockRule] answers the probe instead when set. */
    private class BrokenFilter(val history: Boolean = true, val blockRule: FilterDecision? = null) : MmsSenderCheck {
        override fun probe(): FilterDecision? = blockRule ?: error("filter broken")
        override fun judge(): Judgement? = error("filter broken")
        override fun historyChecked(): Boolean = history
        override fun log(decision: String, reason: String, ruleId: Long?) = Unit
    }

    private fun handler(check: MmsSenderCheck) = handler().apply {
        senderCheck = { _: Context, _: IncomingMessage, _: Long -> check }
    }

    private fun handler(
        probe: FilterDecision? = FilterDecision.Allow(DecisionReason.CONTACT),
        judgement: Judgement? = Judgement(SenderVerdict.Messages(DecisionReason.CONTACT)),
    ) = MmsDownloadHandler().apply {
        incomingMms = { IncomingMms(store) }
        senderCheck = { _: Context, _: IncomingMessage, _: Long -> Check(probe, judgement) { judged++ } }
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
    fun mT5_aFilterErrorOnAOneToOneMms_endsInMessagesUnread_announcedAndForwarded() {
        handler(BrokenFilter()).placeAndAnnounce(context, mms(12L, SENDER), SENDER)

        assertThat(announced).containsExactly(null)
        assertThat(forwarded).hasSize(1)
        assertThat(store.rowSilent.values).doesNotContain(true)
        assertThat(store.spamRows).isEmpty()
    }

    @Test
    fun mT5_aFilterErrorOnAGroupMms_endsInTheGroupInMessages_announcedAndForwarded() {
        handler(BrokenFilter()).placeAndAnnounce(context, mms(40L, SENDER, OTHER), SENDER)

        assertThat(announced).containsExactly(null)
        assertThat(forwarded).hasSize(1)
        assertThat(store.rowSilent).isEmpty()
    }

    @Test
    fun mT5_aBlockedSendersMms_isNeverForwarded_alsoWhenTheHistoryCheckFailed() {
        val blocked = BrokenFilter(history = false, blockRule = FilterDecision.Block(DecisionReason.BLOCK_RULE, 3L))

        handler(blocked).placeAndAnnounce(context, mms(12L, SENDER), SENDER)

        assertThat(announced).isEmpty()
        assertThat(forwarded).isEmpty()
        assertThat(store.rowSilent.values).containsExactly(true)
    }

    @Test
    fun mT5_aSpamMmsWhoseSpamPlaceFails_isAnnouncedUnreadInMessages_butNeverForwarded() {
        store.failSpamRow = true
        val spam = Judgement(SenderVerdict.Spam(SpamCause.UNKNOWN, DecisionReason.UNKNOWN_SENDER))

        handler(judgement = spam).placeAndAnnounce(context, mms(12L, SENDER), SENDER)

        assertThat(announced).containsExactly(null)
        assertThat(forwarded).isEmpty()
        assertThat(store.rowSilent.values).containsExactly(false)
    }

    @Test
    fun e15_aChatWithTheSenderAndTheUsersOwnNumber_isAGroupAsTheListsShowIt_soMessagesNeverReadAndSilent() {
        val spam = Judgement(SenderVerdict.Spam(SpamCause.UNKNOWN, DecisionReason.UNKNOWN_SENDER))
        val handler = handler(probe = FilterDecision.Block(DecisionReason.UNKNOWN_SENDER), judgement = spam)

        handler.placeAndAnnounce(context, mms(12L, SENDER, OWN), SENDER)

        assertThat(judged).isEqualTo(0)
        assertThat(announced).containsExactly(DecisionReason.UNKNOWN_SENDER)
        assertThat(store.rowSilent).isEmpty()
        assertThat(store.spamRows).isEmpty()
    }

    @Test
    fun e10_oneUnreadableRecipient_makesItAGroup() {
        val spam = Judgement(SenderVerdict.Spam(SpamCause.UNKNOWN, DecisionReason.UNKNOWN_SENDER))

        handler(judgement = spam).placeAndAnnounce(context, mms(12L, SENDER, ""), SENDER)

        assertThat(judged).isEqualTo(0)
        assertThat(store.rowSilent).isEmpty()
    }

    @Test
    fun aDownloadingNoticeOfASenderWithAMessagesChat_andAGroupResult_leavesThatChatInMessagesWithoutASpamRow() {
        store.threads[SENDER] = 20L
        store.threadsWithMessages += 20L
        // A downloading Spam notice keeps the dummy thread (no early Spam row, nothing in the sender's chat).
        val notice = Uri.parse("content://mms/inbox/42")

        handler(probe = FilterDecision.Block(DecisionReason.UNKNOWN_SENDER))
            .placeAndAnnounce(context, mms(40L, SENDER, OTHER), SENDER)

        assertThat(store.spamRows).isEmpty()
        assertThat(store.rowThread[notice]).isNull()
        assertThat(store.forgotten).isEmpty()
        assertThat(announced).hasSize(1)
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

    private fun unreported(receiver: MmsReceiver, file: File, notice: Uri) {
        val broadcast = WatchedBroadcast.attachTo(receiver)
        receiver.onReceive(
            context,
            Intent(context, MmsReceiver::class.java)
                .putExtra(MmsReceivedReceiver.EXTRA_FILE_PATH, file.path)
                .putExtra(MmsReceivedReceiver.EXTRA_LOCATION_URL, "http://mmsc.example.com/msg/unreported")
                .putExtra(MmsReceivedReceiver.EXTRA_TRANSACTION_ID, "T-1")
                .putExtra(MmsReceivedReceiver.EXTRA_URI, notice)
                .putExtra(MmsReceivedReceiver.SUBSCRIPTION_ID, 1),
        )
        assertThat(broadcast.awaitFinished()).isTrue()
    }

    private fun failingReceiver(onProbe: () -> Unit) = MmsReceiver().apply {
        failure = MmsDownloadFailure(
            store = { store },
            probe = { _, _ -> onProbe(); null },
            notify = {},
            toast = {},
            finishLibraryDownload = {},
            log = { _, _, _, _, _ -> },
        )
    }

    @Test
    fun e5_aJunkFile_isAFailedDownload_andTheNoticeStays_withAnMmsProviderPresent() {
        val provider = FakeMmsProvider.install()
        val notice = provider.addNotice(SENDER)
        store.notices[notice] = NoticeRow(FakeMmsSpamStore.NO_CHAT, SENDER)
        var failed = 0
        val receiver = failingReceiver { failed++ }
        val file = File(context.cacheDir, "download.junk.dat").apply { writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6)) }

        unreported(receiver, file, notice)

        assertThat(failed).isEqualTo(1)
        assertThat(store.rowThread[notice]).isEqualTo(store.threads.getValue(SENDER))
        assertThat(provider.exists(notice.lastPathSegment!!.toLong())).isTrue()
    }

    @Test
    fun e17_anMmsTheLibraryStoredButDidNotReport_isPlaced_notFailed() {
        var failed = 0
        var asked: Pair<String, String>? = null
        val receiver = failingReceiver { failed++ }.apply {
            storedRow = { _, id, location, _ -> asked = id to location; Uri.parse("content://mms/77") }
        }
        val file = File(context.cacheDir, "download.junk2.dat").apply { writeBytes(byteArrayOf(9, 8, 7, 6)) }

        unreported(receiver, file, Uri.parse("content://mms/inbox/42"))

        assertThat(failed).isEqualTo(0)
        assertThat(asked).isEqualTo("T-1" to "http://mmsc.example.com/msg/unreported")
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
                log = { _, _, _, _, _ -> },
            )
            storedRow = { _, _, _, _ -> null }
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

    @Test
    fun e22_onlyAnExplicitlyCountedNotice_lowersTheCountOfTheChatItLeaves_andItsMarkIsThenForgotten() {
        val provider = FakeMmsProvider.install()
        val counted = provider.addNotice(SENDER, threadId = 55L, read = true)
        val onlyRead = provider.addNotice(SENDER, threadId = 56L, read = true, transactionId = "T-2")
        store.threadsWithMessages += setOf(55L, 56L)
        store.spamRows[55L] = Triple(SENDER, SpamCause.UNKNOWN, 2)
        store.spamRows[56L] = Triple(SENDER, SpamCause.UNKNOWN, 2)
        val marks = AndroidMmsSpamStore(context)
        marks.markNotice(counted, NoticeMark.Counted(55L))

        listOf(counted, onlyRead).forEach { notice ->
            handler(probe = FilterDecision.Block(DecisionReason.UNKNOWN_SENDER))
                .apply { noticeUri = notice }
                .also { it.isAddressBlocked(context, SENDER) }
                .placeAndAnnounce(context, mms(66L, SENDER, OTHER), SENDER)
        }

        assertThat(store.spamRows[55L]?.third).isEqualTo(1)
        // Read by the user, never counted: nothing is lowered.
        assertThat(store.spamRows[56L]?.third).isEqualTo(2)
        assertThat(marks.markOf(counted)).isNull()
    }

    @Test
    fun e22_aCountedNoticeInTheSameChat_isNotCountedAgainWhenItsMmsIsPlaced() {
        val provider = FakeMmsProvider.install()
        val notice = provider.addNotice(SENDER, threadId = 12L, read = true)
        store.spamRows[12L] = Triple(SENDER, SpamCause.UNKNOWN, 1)
        AndroidMmsSpamStore(context).markNotice(notice, NoticeMark.Counted(12L))
        val spam = Judgement(SenderVerdict.Spam(SpamCause.UNKNOWN, DecisionReason.UNKNOWN_SENDER))

        handler(judgement = spam).apply { noticeUri = notice }
            .also { it.isAddressBlocked(context, SENDER) }
            .placeAndAnnounce(context, mms(12L, SENDER), SENDER)

        assertThat(store.spamRows[12L]?.third).isEqualTo(1)
        assertThat(store.spamEvents).isEqualTo(0)
    }

    private companion object {
        const val SENDER = "+15550001234"
        const val OTHER = "+15550004321"
        const val OWN = "+15550009999"
    }
}

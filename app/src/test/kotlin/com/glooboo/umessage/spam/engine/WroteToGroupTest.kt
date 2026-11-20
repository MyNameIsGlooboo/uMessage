package com.glooboo.umessage.spam.engine

import android.app.Application
import android.net.Uri
import android.provider.Telephony
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.extensions.config
import com.glooboo.umessage.extensions.messagingUtils
import com.glooboo.umessage.helpers.ProviderTruthFlag
import com.glooboo.umessage.messaging.OwnSentRows
import com.glooboo.umessage.messaging.LongTextMms
import com.glooboo.umessage.messaging.sendMessageDetailed
import com.glooboo.umessage.spam.AllowGroup
import com.glooboo.umessage.spam.AllowGroupMember
import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.ForwardFormat
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.MemberSource
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamRule
import com.glooboo.umessage.spam.UMessage
import com.glooboo.umessage.spam.data.DefaultSpamRepository
import com.glooboo.umessage.spam.data.PendingSmsEntity
import com.glooboo.umessage.spam.data.SpamDatabase
import com.glooboo.umessage.spam.data.SpamPlatform
import com.glooboo.umessage.spam.data.SpamSettingsStore
import com.glooboo.umessage.testing.FakeMessageStore
import com.glooboo.umessage.testing.ProviderTruthFlagRule
import com.glooboo.umessage.testing.ShadowRecordingSmsManager
import com.glooboo.umessage.testing.SpamTestWiring
import com.glooboo.umessage.testing.joinAppBackgroundThreads
import com.glooboo.umessage.testing.offMainThread
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.google.common.truth.Truth.assertThat
import com.klinker.android.send_message.Settings
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Step D (6.3): writing to someone allows them. uMessage's own sends (R10: sendToDistinctRecipients only), each
 * recipient of a group sent as separate texts (R11), and replies written by another app or device (R3, the observer)
 * make the number a member of "People you wrote to". Forwards, carrier reports, group MMS, blocked numbers and known
 * senders make none. Every number is made up (555 range).
 */
@RunWith(AndroidJUnit4::class)
class WroteToGroupTest {

    @get:Rule
    val flag = ProviderTruthFlagRule(enabled = true)

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val now = System.currentTimeMillis()
    private lateinit var store: FakeMessageStore
    private lateinit var db: SpamDatabase
    private lateinit var repo: DefaultSpamRepository
    private val contacts = mutableSetOf<String>()

    private object NoPlatform : SpamPlatform {
        override fun notifyBlocked(message: BlockedMessage) = Unit
        override fun deliverRestored(pending: PendingSmsEntity): Long? = null
        override fun deliverRestoredMms(message: BlockedMessage): Long? = null
        override fun removeRestored(id: Long, isMms: Boolean) = false
        override fun deleteMedia(names: Collection<String>) = Unit
        override fun sweepMedia(keep: Set<String>) = Unit
    }

    private val checker = object : ContactChecker {
        override fun isContact(address: String) = address in contacts
        override fun canReadContacts() = true
    }

    @Before
    fun setUp() {
        ShadowRecordingSmsManager.reset()
        OwnSentRows.clear()
        SentRowWatch.resetForTest()
        store = FakeMessageStore.install()
        app.config.sendGroupMessageMMS = false
        app.config.sendLongAsMmsAfterParts = LongTextMms.NEVER
        db = SpamTestWiring.freshSpamDatabase(app)
        val normalizer = NumberNormalizer { "US" }
        repo = DefaultSpamRepository(NoPlatform, db, SpamSettingsStore(app), normalizer)
        val filter = DefaultSpamFilter(
            rules = repo::currentRules,
            settings = repo::currentSettings,
            contacts = checker,
            normalizer = normalizer,
            groups = repo.groupMembership,
        )
        UMessage.filterFactory = { filter }
        UMessage.repositoryFactory = { repo }
    }

    @After
    fun tearDown() {
        joinAppBackgroundThreads()
        SpamTestWiring.uninstall()
        SpamTestWiring.clearSpamDatabase()
        resetSharedMessagesDatabase()
    }

    private fun members(): List<AllowGroupMember> = repo.groupMembership.members()

    private fun wroteTo() = members().filter { it.group == AllowGroup.WROTE }.map { it.address }

    private fun send(text: String, vararg addresses: String) = offMainThread {
        app.sendMessageDetailed(text, addresses.toList(), subId = null, attachments = emptyList())
    }

    private fun block(number: String) = runBlocking {
        repo.addRule(SpamRule(kind = ListKind.BLOCK, type = RuleType.NUMBER, pattern = number))
    }

    private fun smsUri(id: Long): Uri = Uri.withAppendedPath(Telephony.Sms.CONTENT_URI, id.toString())

    private fun mmsUri(id: Long): Uri = Uri.withAppendedPath(Telephony.Mms.CONTENT_URI, id.toString())

    @Test
    fun aReplyToAnUnknownNumberJoinsPeopleYouWroteTo() {
        val outcome = send("On my way", STRANGER)

        assertThat(outcome.handedOff).isTrue()
        val member = members().single()
        assertThat(member.group).isEqualTo(AllowGroup.WROTE)
        assertThat(member.address).isEqualTo(STRANGER)
        assertThat(member.source).isEqualTo(MemberSource.LIVE)
    }

    @Test
    fun aSendFromTheMainThreadJoinsOnAHelperThread() {
        app.sendMessageDetailed("On my way", listOf(STRANGER), subId = null, attachments = emptyList())
        joinAppBackgroundThreads()

        assertThat(wroteTo()).containsExactly(STRANGER)
    }

    @Test
    fun aGroupSentAsSeparateTextsJoinsEachUnknownRecipient() {
        contacts += FRIEND

        send("Dinner at eight", STRANGER, OTHER, FRIEND)

        assertThat(wroteTo()).containsExactly(STRANGER, OTHER)
    }

    @Test
    fun aGroupMmsMakesNoMember() {
        app.config.sendGroupMessageMMS = true

        send("Dinner at eight", STRANGER, OTHER)

        assertThat(members()).isEmpty()
    }

    @Test
    fun aReplyToABlockedNumberChangesNothing() {
        block(STRANGER)

        send("Stop", STRANGER)

        assertThat(members()).isEmpty()
    }

    @Test
    fun aReplyToAContactMakesNoMember() {
        contacts += FRIEND

        send("See you", FRIEND)

        assertThat(members()).isEmpty()
    }

    @Test
    fun aReplyWhileTheFilterIsOffStillJoins() {
        runBlocking { repo.updateSettings { it.copy(filterOn = false) } }

        send("See you", STRANGER)

        assertThat(wroteTo()).containsExactly(STRANGER)
    }

    @Test
    fun aSendWithTheFlagOffMakesNoMember() {
        flagOff()

        send("See you", STRANGER)

        assertThat(members()).isEmpty()
    }

    @Test
    fun aForwardOrCarrierReportSentPastTheHookMakesNoMember() {
        // R10: forwards and carrier reports call sendSmsMessage directly, and the observer skips their own rows.
        offMainThread {
            app.messagingUtils.sendSmsMessage("Spam text", setOf(STRANGER), Settings.DEFAULT_SUBSCRIPTION_ID, false)
        }
        val row = smsIds().single()

        SentRowWatch.handle(app, smsUri(row), now)

        assertThat(members()).isEmpty()
    }

    @Test
    fun aReplyWrittenByAnotherAppJoins() {
        // R3: a car kit, a watch or another app; the system writes the sent row.
        val id = store.addSms(STRANGER, "On my way", now, type = Telephony.Sms.MESSAGE_TYPE_SENT)

        SentRowWatch.handle(app, smsUri(id), now + 1_000L)

        assertThat(wroteTo()).containsExactly(STRANGER)
    }

    @Test
    fun theObserverSkipsAForwardText() {
        val body = ForwardFormat.format("+15550009999", "Your parcel is here", isMms = false)
        val id = store.addSms(STRANGER, body, now, type = Telephony.Sms.MESSAGE_TYPE_SENT)

        SentRowWatch.handle(app, smsUri(id), now)

        assertThat(members()).isEmpty()
    }

    @Test
    fun theObserverSkipsReceivedAndOldRows() {
        val received = store.addSms(STRANGER, "Hello", now, type = Telephony.Sms.MESSAGE_TYPE_INBOX)
        val old = store.addSms(OTHER, "Hello", now - HOUR_MS, type = Telephony.Sms.MESSAGE_TYPE_SENT)

        SentRowWatch.handle(app, smsUri(received), now)
        SentRowWatch.handle(app, smsUri(old), now)

        assertThat(members()).isEmpty()
    }

    @Test
    fun theObserverJoinsTheOneRecipientOfASentMmsButNoGroupMms() {
        val single = store.addMms(1L, now / 1000, Telephony.Mms.MESSAGE_BOX_SENT, "Photo", null, listOf(STRANGER to TO))
        val group = store.addMms(
            2L, now / 1000, Telephony.Mms.MESSAGE_BOX_SENT, "Photo", null, listOf(OTHER to TO, FRIEND to TO),
        )

        SentRowWatch.handle(app, mmsUri(single), now)
        SentRowWatch.handle(app, mmsUri(group), now)

        assertThat(wroteTo()).containsExactly(STRANGER)
    }

    @Test
    fun theObserverDoesNothingWithTheFlagOff() {
        flagOff()
        val id = store.addSms(STRANGER, "On my way", now, type = Telephony.Sms.MESSAGE_TYPE_SENT)

        SentRowWatch.handle(app, smsUri(id), now)

        assertThat(members()).isEmpty()
    }

    @Test
    fun onlyUnknownSendersAndFilterOffCountAsUnknown() {
        assertThat(WroteToGroup.isUnknown(SenderVerdict.Spam(SpamCause.UNKNOWN, DecisionReason.UNKNOWN_SENDER)))
            .isTrue()
        assertThat(WroteToGroup.isUnknown(SenderVerdict.Messages(DecisionReason.MODE_OFF))).isTrue()
        assertThat(WroteToGroup.isUnknown(SenderVerdict.Spam(SpamCause.BLOCKED, DecisionReason.BLOCK_RULE))).isFalse()
        assertThat(WroteToGroup.isUnknown(SenderVerdict.Messages(DecisionReason.CONTACT))).isFalse()
        assertThat(WroteToGroup.isUnknown(SenderVerdict.Messages(DecisionReason.GROUP_MEMBER))).isFalse()
        assertThat(WroteToGroup.isUnknown(SenderVerdict.Messages(DecisionReason.UNREADABLE_SENDER))).isFalse()
    }

    private fun flagOff() {
        ProviderTruthFlag.enabled = false
    }

    private fun smsIds(): List<Long> =
        app.contentResolver.query(Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms._ID), null, null, null)
            ?.use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }.orEmpty()

    private companion object {
        const val STRANGER = "+15550001234"
        const val OTHER = "+15550001235"
        const val FRIEND = "+15550001236"
        const val HOUR_MS = 60L * 60 * 1000

        /** PduHeaders.TO. */
        const val TO = 151
    }
}

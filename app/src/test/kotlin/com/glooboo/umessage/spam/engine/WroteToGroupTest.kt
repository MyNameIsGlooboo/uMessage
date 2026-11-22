package com.glooboo.umessage.spam.engine

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.Telephony
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.databases.MessagesDatabase
import com.glooboo.umessage.extensions.config
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.extensions.getThreadId
import com.glooboo.umessage.models.Attachment
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.models.MessageKey
import com.glooboo.umessage.spam.ChatSorter
import com.glooboo.umessage.spam.SortResult
import com.glooboo.umessage.spam.SpamThread
import com.glooboo.umessage.spam.data.RemovedMembers
import com.glooboo.umessage.extensions.messagingUtils
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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.shadows.ShadowLooper

/**
 * Step D (6.3): writing to someone allows them. uMessage's own sends (R10: sendToDistinctRecipients only), each
 * recipient of a group sent as separate texts (R11), and replies written by another app or device (R3, the observer)
 * make the number a member of "People you wrote to". Forwards, carrier reports, group MMS, blocked numbers and known
 * senders make none. Every number is made up (555 range).
 */
@RunWith(AndroidJUnit4::class)
class WroteToGroupTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val now = System.currentTimeMillis()
    private lateinit var store: FakeMessageStore
    private lateinit var db: SpamDatabase
    private lateinit var repo: DefaultSpamRepository
    private lateinit var filter: DefaultSpamFilter
    private val contacts = mutableSetOf<String>()

    /** An SMS store with the creator column and a switch that refuses inserts (nothing is saved). */
    class SendStore : ContentProvider() {
        private lateinit var db: SQLiteDatabase

        @Volatile var refuseInserts = false

        override fun onCreate(): Boolean {
            db = SQLiteDatabase.create(null)
            db.execSQL(
                "CREATE TABLE sms (_id INTEGER PRIMARY KEY AUTOINCREMENT, address TEXT, body TEXT, date INTEGER, " +
                    "date_sent INTEGER, read INTEGER, seen INTEGER, thread_id INTEGER, type INTEGER, " +
                    "sub_id INTEGER DEFAULT -1, status INTEGER DEFAULT -1, creator TEXT)"
            )
            return true
        }

        fun addSent(address: String, date: Long, creator: String): Long = db.insert("sms", null, ContentValues().apply {
            put("address", address)
            put("body", "On my way")
            put("date", date)
            put("type", Telephony.Sms.MESSAGE_TYPE_SENT)
            put("creator", creator)
        })

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? = when (uri.authority) {
            "mms-sms" -> MatrixCursor(arrayOf("_id")).apply { addRow(arrayOf<Any>(THREAD)) }
            "sms" -> db.query("sms", projection, selection, selectionArgs, null, null, sortOrder)
            else -> null
        }

        override fun insert(uri: Uri, values: ContentValues?): Uri? {
            if (uri.authority != "sms" || refuseInserts) return null
            val id = db.insert("sms", null, ContentValues(values))
            return Uri.withAppendedPath(Telephony.Sms.CONTENT_URI, id.toString())
        }

        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) =
            if (uri.authority == "sms") db.update("sms", values, selection, args) else 0

        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
        override fun getType(uri: Uri): String? = null
    }

    private fun sendStore(): SendStore = Robolectric.buildContentProvider(SendStore::class.java)
        .create(ProviderInfo().apply { authority = "sms;mms-sms" })
        .get()

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
        RemovedMembers.reset()
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
        this.filter = filter
        UMessage.filterFactory = { filter }
        UMessage.repositoryFactory = { repo }
    }

    @After
    fun tearDown() {
        joinAppBackgroundThreads()
        RemovedMembers.reset()
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
    fun onlyUnknownSendersAndFilterOffCountAsUnknown() {
        assertThat(WroteToGroup.isUnknown(SenderVerdict.Spam(SpamCause.UNKNOWN, DecisionReason.UNKNOWN_SENDER)))
            .isTrue()
        assertThat(WroteToGroup.isUnknown(SenderVerdict.Messages(DecisionReason.MODE_OFF))).isTrue()
        assertThat(WroteToGroup.isUnknown(SenderVerdict.Spam(SpamCause.BLOCKED, DecisionReason.BLOCK_RULE))).isFalse()
        assertThat(WroteToGroup.isUnknown(SenderVerdict.Messages(DecisionReason.CONTACT))).isFalse()
        assertThat(WroteToGroup.isUnknown(SenderVerdict.Messages(DecisionReason.GROUP_MEMBER))).isFalse()
        assertThat(WroteToGroup.isUnknown(SenderVerdict.Messages(DecisionReason.UNREADABLE_SENDER))).isFalse()
    }

    @Test
    fun aSendThatSavedNothingTakesItsNewMemberBack() {
        // D-6: the store refuses the row, so nothing went out; the member this send made goes again.
        sendStore().refuseInserts = true

        val outcome = send("On my way", STRANGER)

        assertThat(outcome.handedOff).isFalse()
        assertThat(members()).isEmpty()
    }

    @Test
    fun aSendThatSavedNothingKeepsAMemberMadeEarlier() {
        runBlocking { repo.joinGroup(AllowGroup.WROTE, STRANGER, MemberSource.HISTORY) }
        sendStore().refuseInserts = true

        send("On my way", STRANGER)

        assertThat(wroteTo()).containsExactly(STRANGER)
    }

    @Test
    fun theObserverSkipsARowUMessageWroteItselfAlsoAfterARestart() {
        // D-5: the creator column says so, though the in-memory list of own rows is empty (a new process).
        val sends = sendStore()
        val own = sends.addSent(STRANGER, now, app.packageName)
        val carKit = sends.addSent(OTHER, now, "com.example.carkit")

        SentRowWatch.handle(app, smsUri(own), now)
        SentRowWatch.handle(app, smsUri(carKit), now)

        assertThat(wroteTo()).containsExactly(OTHER)
    }

    @Test
    fun theStoresObserverHandsANewSentRowToTheWatch() {
        // The wiring: SystemContactChecker's observer of the message store reaches SentRowWatch.
        val before = SentRowWatch.delayMs
        SentRowWatch.delayMs = 0L
        val checker = SystemContactChecker(app, NumberNormalizer { "US" })
        try {
            val id = store.addSms(STRANGER, "On my way", now, type = Telephony.Sms.MESSAGE_TYPE_SENT)
            app.contentResolver.notifyChange(smsUri(id), null)
            ShadowLooper.idleMainLooper()
            SentRowWatch.drain()

            assertThat(wroteTo()).containsExactly(STRANGER)
        } finally {
            checker.close()
            SentRowWatch.delayMs = before
        }
    }

    @Test
    fun aFailedSendKeepsAMemberThatExistedWhileTheGroupIsOff() {
        // E-1: with "People you wrote to" off a member's verdict is unknown; the failed send must not remove it.
        runBlocking { repo.joinGroup(AllowGroup.WROTE, STRANGER, MemberSource.LIVE) }
        runBlocking { repo.updateSettings { it.copy(wroteGroupOn = false) } }
        sendStore().refuseInserts = true

        send("On my way", STRANGER)

        assertThat(wroteTo()).containsExactly(STRANGER)
    }

    @Test
    fun aFailedSendPutsBackTheRemoveMarkItsJoinCleared() {
        val readers = com.glooboo.umessage.spam.SpamThreads.readers(app)
        val keys = readers.senderKeys(STRANGER) + readers.senderKey(STRANGER)
        RemovedMembers.add(AllowGroup.WROTE, keys)
        sendStore().refuseInserts = true

        send("On my way", STRANGER)

        assertThat(members()).isEmpty()
        assertThat(RemovedMembers.contains(AllowGroup.WROTE, keys)).isTrue()
    }

    @Test
    fun aBundledResendThatSendsNothingTakesItsNewMemberBack() {
        // Reviewer: the early return of the bundled MMS resend undoes the join too. Unreadable pictures send nothing.
        val pictures = listOf(
            Attachment(null, 0L, "content://nowhere/1", "image/jpeg", 0, 0, "a.jpg"),
            Attachment(null, 0L, "content://nowhere/2", "image/jpeg", 0, 0, "b.jpg"),
        )

        val outcome = offMainThread {
            app.sendMessageDetailed("Photos", listOf(STRANGER), null, pictures, resend = MessageKey(RESEND_ID, true))
        }

        assertThat(outcome.handedOff).isFalse()
        assertThat(outcome.sentCount).isEqualTo(0)
        assertThat(members()).isEmpty()
    }

    @Test
    fun aReplyMovesASpamChatOutAtItsNextSort() {
        val thread = offMainThread { app.getThreadId(STRANGER) }
        offMainThread {
            app.conversationsDB.insertOrUpdate(Conversation(thread, "Sale", 100, true, "Chat", "", false, STRANGER))
            app.conversationsDB.insertSpamThread(SpamThread(thread, STRANGER, SpamCause.UNKNOWN, spamSince = 1L))
        }

        send("Who is this?", STRANGER)
        val sorted = offMainThread {
            ChatSorter(MessagesDatabase.getInstance(app), { filter.senderJudge.snapshot() }, { true })
                .recompute(setOf(thread))
        }

        assertThat(sorted).isInstanceOf(SortResult.Applied::class.java)
        assertThat(offMainThread { app.conversationsDB.getSpamThread(thread) }).isNull()
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
        const val THREAD = 7L
        const val RESEND_ID = 5L
    }
}

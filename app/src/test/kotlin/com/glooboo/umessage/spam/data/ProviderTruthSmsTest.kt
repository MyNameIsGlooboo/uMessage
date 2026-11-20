package com.glooboo.umessage.spam.data

import android.app.Application
import android.app.NotificationManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.Telephony
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.extensions.config
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.extensions.messagesDB
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.helpers.ProviderTruthFlag
import com.glooboo.umessage.spam.AllowGroup
import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.FilterDecision
import com.glooboo.umessage.spam.FirstRun
import com.glooboo.umessage.spam.FirstRunPhase
import com.glooboo.umessage.spam.FirstRunState
import com.glooboo.umessage.spam.FirstRunStore
import com.glooboo.umessage.spam.ForwardRule
import com.glooboo.umessage.spam.MemberSource
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamRule
import com.glooboo.umessage.spam.SpamSettings
import com.glooboo.umessage.spam.SpamThread
import com.glooboo.umessage.spam.SpamThreads
import com.glooboo.umessage.spam.UMessage
import com.glooboo.umessage.spam.engine.ContactChecker
import com.glooboo.umessage.spam.engine.ContactLookup
import com.glooboo.umessage.spam.engine.DefaultSpamFilter
import com.glooboo.umessage.spam.engine.Judgement
import com.glooboo.umessage.spam.engine.LegacyBlocklist
import com.glooboo.umessage.spam.engine.NumberNormalizer
import com.glooboo.umessage.spam.engine.SenderVerdict
import com.glooboo.umessage.spam.engine.SpamCause
import com.glooboo.umessage.spam.engine.SpamEngine
import com.glooboo.umessage.databases.MessagesDatabase
import com.glooboo.umessage.receivers.SmsReceiver
import com.glooboo.umessage.testing.ProviderTruthFlagRule
import com.glooboo.umessage.testing.ShadowRecordingSmsManager
import com.glooboo.umessage.testing.joinAppBackgroundThreads
import com.glooboo.umessage.testing.SpamTestWiring
import com.glooboo.umessage.testing.offMainThread
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.glooboo.umessage.ui.spam.RuleNotes
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import java.util.Locale

/**
 * Step D (6.1): an incoming SMS under the provider-truth flag. Spam is written to the phone's store read, seen and
 * silent, with its spam_threads row first; Messages texts stay unread and announced; the safety check (R1) never
 * leaves a read and silent text outside Spam. The store is in memory; the filter and repository are the real ones on
 * an in-memory spamless.db. Every number is made up (555 range).
 */
@RunWith(AndroidJUnit4::class)
class ProviderTruthSmsTest {

    @get:Rule
    val flag = ProviderTruthFlagRule(enabled = true)

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val manager get() = app.getSystemService(NotificationManager::class.java)
    private val posted get() = shadowOf(manager).allNotifications
    private val realCheckSender = ArrivalFacts.checkSender
    private val realWroteTo = ArrivalFacts.wroteTo
    private val checkedSenders = mutableListOf<String>()
    private var historyAnswer = true
    private var wroteAnswer = ContactLookup.NOT_FOUND
    private lateinit var store: SpamStore
    private lateinit var db: SpamDatabase
    private lateinit var repo: DefaultSpamRepository
    private val now = System.currentTimeMillis()

    /** The SMS provider and the thread lookup, in memory; [afterInsert] runs right after each SMS insert. */
    class SpamStore : ContentProvider() {
        private lateinit var db: SQLiteDatabase
        private val threads = HashMap<String, Long>()

        @Volatile var afterInsert: (() -> Unit)? = null

        /** How many of the next SMS inserts fail the way a broken provider does (insert returns null). */
        @Volatile var failNextInserts = 0

        /** While true the thread lookup answers nothing, so the app's thread id is 0. */
        @Volatile var failThreadIds = false

        override fun onCreate(): Boolean {
            db = SQLiteDatabase.create(null)
            db.execSQL(
                "CREATE TABLE sms (_id INTEGER PRIMARY KEY AUTOINCREMENT, address TEXT, subject TEXT, body TEXT, " +
                    "date INTEGER, date_sent INTEGER DEFAULT 0, read INTEGER DEFAULT 0, seen INTEGER DEFAULT 0, " +
                    "thread_id INTEGER, type INTEGER DEFAULT 1, sub_id INTEGER DEFAULT -1, status INTEGER DEFAULT -1)"
            )
            return true
        }

        fun rows(): List<Map<String, Any?>> = db.rawQuery("SELECT * FROM sms ORDER BY _id", null).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add((0 until c.columnCount).associate { c.getColumnName(it) to c.getString(it) })
                }
            }
        }

        fun addInbox(address: String, body: String, date: Long, threadId: Long, read: Int): Long =
            db.insert("sms", null, ContentValues().apply {
                put("address", address)
                put("body", body)
                put("date", date)
                put("thread_id", threadId)
                put("type", Telephony.Sms.MESSAGE_TYPE_INBOX)
                put("read", read)
                put("seen", read)
            })

        fun threadOf(address: String): Long = synchronized(threads) { threads.getOrPut(address) { 40L + threads.size } }

        /** The phone's loose matching puts [address] into [threadId] (two numbers in one thread). */
        fun share(address: String, threadId: Long) = synchronized(threads) { threads[address] = threadId }

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? = when {
            uri.authority == "mms-sms" && uri.pathSegments.firstOrNull() == "threadID" ->
                MatrixCursor(arrayOf("_id")).apply {
                    if (!failThreadIds) addRow(arrayOf<Any>(threadOf(uri.getQueryParameter("recipient")!!)))
                }
            uri.authority == "sms" -> {
                val box = if (uri.pathSegments.firstOrNull() == "inbox") "type = 1" else null
                val where = listOfNotNull(box, selection?.let { "($it)" }).joinToString(" AND ").ifEmpty { null }
                db.query("sms", projection, where, selectionArgs, null, null, sortOrder)
            }
            else -> null
        }

        override fun insert(uri: Uri, values: ContentValues?): Uri? {
            if (uri.authority != "sms") return null
            if (failNextInserts > 0) {
                failNextInserts--
                return null
            }
            val id = db.insert("sms", null, ContentValues(values))
            afterInsert?.invoke()
            return if (id > 0) Uri.withAppendedPath(Telephony.Sms.CONTENT_URI, id.toString()) else null
        }

        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int =
            if (uri.authority == "sms") db.update("sms", values, selection, args) else 0

        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
        override fun getType(uri: Uri): String? = null
    }

    private object NoPlatform : SpamPlatform {
        override fun notifyBlocked(message: BlockedMessage) = Unit
        override fun deliverRestored(pending: PendingSmsEntity): Long? = null
        override fun deliverRestoredMms(message: BlockedMessage): Long? = null
        override fun removeRestored(id: Long, isMms: Boolean) = false
        override fun deleteMedia(names: Collection<String>) = Unit
        override fun sweepMedia(keep: Set<String>) = Unit
    }

    /** Nobody is a contact, and contacts can be read. */
    private object Strangers : ContactChecker {
        override fun isContact(address: String) = false
        override fun lookup(address: String) = ContactLookup.NOT_FOUND
        override fun canReadContacts() = true
    }

    /** The old Fossify word list of these tests. */
    private var legacyWords = emptyList<String>()

    private val legacy = object : LegacyBlocklist {
        override fun isNumberBlocked(address: String) = false
        override fun blockedKeywords(): Collection<String> = legacyWords
        override fun blockUnknownNumbers() = false
    }

    @Before
    fun setUp() {
        resetSpamEngine()
        store = Robolectric.buildContentProvider(SpamStore::class.java)
            .create(ProviderInfo().apply { authority = "sms;mms-sms" })
            .get()
        db = SpamTestWiring.freshSpamDatabase(app)
        SpamTestWiring.resetPendingLeases()
        NotifyDue.resetForTest(app)
        ShadowRecordingSmsManager.reset()
        val normalizer = NumberNormalizer { "US" }
        repo = DefaultSpamRepository(
            NoPlatform, db, SpamSettingsStore(app), normalizer,
            blockedFromKeywordNote = { RuleNotes.BLOCKED_FROM_KEYWORD },
            allowedFromKeywordNote = { RuleNotes.ALLOWED_FROM_KEYWORD },
        )
        val filter = DefaultSpamFilter(
            rules = repo::currentRules,
            settings = repo::currentSettings,
            contacts = Strangers,
            normalizer = normalizer,
            legacy = legacy,
            groups = repo.groupMembership,
        )
        UMessage.filterFactory = { filter }
        UMessage.repositoryFactory = { repo }
        // The history check itself is step B's; here it records who was checked and answers [historyAnswer].
        ArrivalFacts.checkSender = { _, address, _ ->
            checkedSenders += address
            historyAnswer
        }
        ArrivalFacts.wroteTo = { _, _ -> wroteAnswer }
        // The first run is done and published unless a test says otherwise.
        firstRun(FirstRunState(phase = FirstRunPhase.DONE, sortPublished = true))
    }

    @After
    fun tearDown() {
        joinAppBackgroundThreads()
        ArrivalFacts.checkSender = realCheckSender
        ArrivalFacts.wroteTo = realWroteTo
        firstRun(FirstRunState())
        NotifyDue.resetForTest(app)
        SpamTestWiring.uninstall()
        SpamTestWiring.clearSpamDatabase()
        ShadowRecordingSmsManager.reset()
        resetSharedMessagesDatabase()
        resetSpamEngine()
    }

    /** SpamEngine keeps singletons made with an earlier test's Application; they are made again for this one. */
    private fun resetSpamEngine() {
        listOf("repository", "filter", "settingsStore", "forwardPlanner", "checker").forEach { name ->
            runCatching { SpamEngine::class.java.getDeclaredField(name).apply { isAccessible = true }.set(null, null) }
        }
    }

    private fun firstRun(state: FirstRunState) {
        FirstRunStore(app.getSharedPreferences(FirstRunStore.PREFS, Context.MODE_PRIVATE)).write(state)
        FirstRun.reload(app)
    }

    /** A chat the app has cached for [threadId], under [number]. */
    private fun cache(number: String, threadId: Long, archived: Boolean = false) = offMainThread {
        app.conversationsDB.insertOrUpdate(
            Conversation(threadId, "Earlier", 100, true, "Chat", "", false, number, isArchived = archived)
        )
    }

    private fun spamList() = offMainThread { app.conversationsDB.getSpamList().map { it.threadId } }

    private fun messagesList() = offMainThread { app.conversationsDB.getMessagesList().map { it.threadId } }

    private fun forwardTo(target: String) {
        db.forwardDao().insert(
            ForwardRuleEntity.from(
                ForwardRule(senderType = RuleType.NUMBER, senderPattern = STRANGER, targets = listOf(target))
            )
        )
    }

    private fun forwards() = ShadowRecordingSmsManager.textSends.map { it.destination }

    private fun inbox() = store.rows().filter { it.int("type") == Telephony.Sms.MESSAGE_TYPE_INBOX }

    private fun receive(body: String, address: String = STRANGER, at: Long = now) = offMainThread {
        IncomingSmsProcessor.process(app, message(body, address, at))
    }

    private fun message(body: String, address: String = STRANGER, at: Long = now, createdAt: Long = at) =
        PendingSmsEntity(
            address = address, subject = "", body = body, date = at, subscriptionId = 1,
            status = Telephony.Sms.STATUS_NONE, createdAt = createdAt,
        )

    private fun settings(change: (SpamSettings) -> SpamSettings) =
        runBlocking { repo.updateSettings(change) }

    private fun spamRow(address: String = STRANGER) =
        offMainThread { app.conversationsDB.getSpamThread(store.threadOf(address)) }

    private fun blockedEvents() = runBlocking { db.spamDao().observeEventCount(SpamEventType.BLOCKED).first() }

    private fun Map<String, Any?>.int(column: String) = (get(column) as String?)?.toInt()

    @Test
    fun anUnknownSendersTextIsStoredReadSeenAndSilentInSpam() {
        receive("Big sale today only")

        val row = store.rows().single()
        assertThat(row.int("read")).isEqualTo(1)
        assertThat(row.int("seen")).isEqualTo(1)
        assertThat(row.int("thread_id")).isEqualTo(store.threadOf(STRANGER).toInt())
        val spam = spamRow()!!
        assertThat(spam.cause).isEqualTo(SpamCause.UNKNOWN)
        assertThat(spam.unread).isEqualTo(1)
        assertThat(posted).isEmpty()
        assertThat(NotifyDue.pendingIds(app)).isEmpty()
        // Nothing goes to the old store any more; the "blocked" count still grows.
        assertThat(db.spamDao().getAllBlocked()).isEmpty()
        assertThat(blockedEvents()).isEqualTo(1)
    }

    @Test
    fun eachSpamTextRaisesTheChatsOwnUnreadCount() {
        receive("Big sale today only")
        receive("Last chance", at = now + 60_000L)

        assertThat(spamRow()!!.unread).isEqualTo(2)
        assertThat(store.rows().map { it.int("read") }).containsExactly(1, 1)
        assertThat(posted).isEmpty()
    }

    @Test
    fun aBlockedSenderNeverNotifiesNotEvenForACodeOrWithTheOptionOn() {
        settings { it.copy(notifyOnBlock = true) }
        val ruleId = runBlocking {
            repo.addRule(SpamRule(kind = ListKind.BLOCK, type = RuleType.NUMBER, pattern = STRANGER))
        }

        receive("Your verification code is 482915")

        assertThat(store.rows().single().int("read")).isEqualTo(1)
        assertThat(spamRow()!!.cause).isEqualTo(SpamCause.BLOCKED)
        assertThat(posted).isEmpty()
        assertThat(repo.groupMembership.members()).isEmpty()
        assertThat(db.spamDao().getRules().single { it.id == ruleId }.hitCount).isEqualTo(1)
    }

    @Test
    fun withTheOptionOnAnUnknownSenderGetsOneQuietNoticePerChat() {
        settings { it.copy(notifyOnBlock = true) }

        receive("Big sale today only")
        receive("Last chance", at = now + 60_000L)

        val notice = posted.single()
        assertThat(notice.channelId).isEqualTo("spamless_blocked")
        val shown = manager.activeNotifications.single()
        assertThat(shown.tag).isEqualTo("spamless_blocked_chat")
        assertThat(shown.id).isEqualTo(store.threadOf(STRANGER).hashCode())
        // Still read and silent in the store.
        assertThat(store.rows().map { it.int("read") }).containsExactly(1, 1)
        assertThat(NotifyDue.pendingIds(app)).isEmpty()
    }

    @Test
    fun aWeakCodeFromAnUnknownSenderStaysSilentInSpam() {
        // Owner decision 20 Nov (B): a code too weak to allow its sender stays in Spam, silent.
        receive("Use 664422 now")

        assertThat(store.rows().single().int("read")).isEqualTo(1)
        assertThat(spamRow()).isNotNull()
        assertThat(posted).isEmpty()
    }

    @Test
    fun aVerificationCodeAllowsTheSenderAndIsAnnouncedInMessages() {
        receive("Your verification code is 482915")

        assertThat(store.rows().single().int("read")).isEqualTo(0)
        assertThat(spamRow()).isNull()
        assertThat(posted).hasSize(1)
        assertThat(repo.groupMembership.members().map { it.group }).containsExactly(AllowGroup.CODE)
        assertThat(NotifyDue.pendingIds(app)).isEmpty()
    }

    @Test
    fun withTheFilterOffAnUnknownSendersTextIsUnreadInMessages() {
        settings { it.copy(filterOn = false) }

        receive("Big sale today only")

        assertThat(store.rows().single().int("read")).isEqualTo(0)
        assertThat(spamRow()).isNull()
        assertThat(posted).hasSize(1)
    }

    @Test
    fun aTextWithoutASenderIsShownInMessages() {
        receive("Network notice", address = "Unknown")

        assertThat(store.rows().single().int("read")).isEqualTo(0)
        assertThat(spamRow("Unknown")).isNull()
    }

    @Test
    fun aFailedHistoryCheckFailsOpenToMessages() {
        historyAnswer = false
        firstRun(FirstRunState())

        receive("Big sale today only")

        assertThat(store.rows().single().int("read")).isEqualTo(0)
        assertThat(spamRow()).isNull()
        assertThat(posted).hasSize(1)
    }

    @Test
    fun aBrokenFilterFailsOpenToMessages() {
        UMessage.filterFactory = { error("filter broke") }

        receive("Big sale today only")

        assertThat(store.rows().single().int("read")).isEqualTo(0)
        assertThat(posted).hasSize(1)
    }

    @Test
    fun theSafetyCheckAnnouncesASilentTextWhoseChatLeftSpam() {
        // R1: something (a sort, a rebuilt store) takes the chat out of Spam between the row and the check.
        store.afterInsert = { app.conversationsDB.deleteSpamThread(store.threadOf(STRANGER)) }

        receive("Big sale today only")

        val row = store.rows().single()
        assertThat(row.int("read")).isEqualTo(0)
        assertThat(row.int("seen")).isEqualTo(0)
        assertThat(posted).hasSize(1)
        assertThat(NotifyDue.pendingIds(app)).isEmpty()
    }

    @Test
    fun theSameSpamTextWithinTenSecondsIsStoredOnce() {
        receive("Big sale today only")
        receive("Big sale today only", at = now + 5_000L)

        assertThat(store.rows()).hasSize(1)
        assertThat(spamRow()!!.unread).isEqualTo(1)
    }

    @Test
    fun theSameTextLaterIsStoredAgain() {
        receive("Big sale today only")
        receive("Big sale today only", at = now + 20_000L)

        assertThat(store.rows()).hasSize(2)
    }

    @Test
    fun aDuplicateInMessagesIsNeverDropped() {
        receive("Your verification code is 482915")
        receive("Your verification code is 482915", at = now + 5_000L)

        assertThat(store.rows()).hasSize(2)
    }

    @Test
    fun theReplayAnnouncesAStoredSilentRowWhoseChatIsNotInSpam() {
        // The process died after the silent insert (its mark is on disk); meanwhile the chat left Spam.
        val thread = store.threadOf(STRANGER)
        store.addInbox(STRANGER, "Big sale today only", now, thread, read = 1)
        SilentMarks.mark(app, message("Big sale today only"))
        offMainThread {
            db.spamDao().insertPending(message("Big sale today only", createdAt = now - PendingSmsPolicy.GRACE_MS - 1))
        }
        settings { it.copy(filterOn = false) }

        offMainThread { PendingSmsQueue.replay(app, now) }

        assertThat(store.rows().single().int("read")).isEqualTo(0)
        assertThat(posted).hasSize(1)
        assertThat(db.spamDao().getPending()).isEmpty()
    }

    @Test
    fun theReplayLeavesAStoredSilentRowThatIsInSpam() {
        val thread = store.threadOf(STRANGER)
        offMainThread { SpamThreads.noteArrival(app, thread, STRANGER, SpamCause.UNKNOWN, now) }
        store.addInbox(STRANGER, "Big sale today only", now, thread, read = 1)
        SilentMarks.mark(app, message("Big sale today only"))
        offMainThread {
            db.spamDao().insertPending(message("Big sale today only", createdAt = now - PendingSmsPolicy.GRACE_MS - 1))
        }

        offMainThread { PendingSmsQueue.replay(app, now) }

        assertThat(store.rows().single().int("read")).isEqualTo(1)
        assertThat(posted).isEmpty()
    }

    @Test
    fun aSpamTextTheStoreRefusesIsKeptInTheQueueAndStoredOnceLater() {
        // M-T5: both inserts fail (the full one and the bare one): the caller keeps the pending row.
        val row = message("Big sale today only", createdAt = now - PendingSmsPolicy.GRACE_MS - 1)
        offMainThread { db.spamDao().insertPending(row) }
        store.failNextInserts = 2

        assertThat(offMainThread { PendingSmsQueue.replay(app, now) }).isGreaterThan(0)
        assertThat(store.rows()).isEmpty()
        assertThat(db.spamDao().getPending()).hasSize(1)

        offMainThread { PendingSmsQueue.replay(app, now) }

        val stored = store.rows().single()
        assertThat(stored.int("read")).isEqualTo(1)
        assertThat(stored.int("seen")).isEqualTo(1)
        assertThat(spamRow()).isNotNull()
        assertThat(db.spamDao().getPending()).isEmpty()
        assertThat(posted).isEmpty()
    }

    @Test
    fun aSpamTextStoredByTheBareInsertIsStillReadAndSilentInSpam() {
        // M-T5: the full insert fails, the bare one takes it.
        store.failNextInserts = 1

        receive("Big sale today only")

        val stored = store.rows().single()
        assertThat(stored.int("read")).isEqualTo(1)
        assertThat(stored.int("seen")).isEqualTo(1)
        assertThat(spamRow()).isNotNull()
        assertThat(posted).isEmpty()
    }

    @Test
    fun aMessagesTextStoredByTheBareInsertIsAnnouncedByTheReplay() {
        // M-T5 with the notification hotfix: the bare insert leaves a "notify due" marker; the replay posts it.
        store.failNextInserts = 1

        receive("Your verification code is 482915")

        assertThat(store.rows().single().int("read")).isEqualTo(0)
        assertThat(NotifyDue.pendingIds(app)).hasSize(1)
        offMainThread { PendingSmsQueue.replay(app) }
        assertThat(posted).hasSize(1)
        assertThat(NotifyDue.pendingIds(app)).isEmpty()
    }

    // ---- D-1: the place follows the rule the lists use (plan 12.1: two numbers in one thread, region mismatch) ----

    @Test
    fun aSpamTextInAChatCachedUnderAnotherNumberIsAnnouncedInMessages() {
        // The phone put OTHER's chat and STRANGER's text into one thread; the chat is cached as OTHER, in Messages.
        val thread = store.threadOf(OTHER)
        store.share(STRANGER, thread)
        cache(OTHER, thread)

        receive("Big sale today only")

        assertThat(inbox().single().int("read")).isEqualTo(0)
        assertThat(posted).hasSize(1)
        assertThat(offMainThread { app.conversationsDB.getSpamThread(thread) }).isNull()
        assertThat(messagesList()).contains(thread)
        assertThat(spamList()).doesNotContain(thread)
    }

    @Test
    fun aValidSpamRowIsNeverReplacedByATextOfAnotherNumber() {
        val thread = store.threadOf(OTHER)
        store.share(STRANGER, thread)
        cache(OTHER, thread)
        offMainThread {
            app.conversationsDB.insertSpamThread(SpamThread(thread, OTHER, SpamCause.BLOCKED, spamSince = 5L))
        }

        receive("Big sale today only")

        val row = offMainThread { app.conversationsDB.getSpamThread(thread) }!!
        assertThat(row.sender).isEqualTo(OTHER)
        assertThat(row.cause).isEqualTo(SpamCause.BLOCKED)
        assertThat(row.spamSince).isEqualTo(5L)
        assertThat(row.unread).isEqualTo(1)
        assertThat(inbox().single().int("read")).isEqualTo(1)
        assertThat(spamList()).contains(thread)
        assertThat(posted).isEmpty()
    }

    @Test
    fun aChatCachedUnderTheSameNumberWrittenAnotherWayGoesToSpamUnderThatNumber() {
        val before = Locale.getDefault()
        Locale.setDefault(Locale.US)
        try {
            val thread = store.threadOf(STRANGER)
            cache(NATIONAL, thread)

            receive("Big sale today only")

            assertThat(offMainThread { app.conversationsDB.getSpamThread(thread) }!!.sender).isEqualTo(NATIONAL)
            assertThat(spamList()).contains(thread)
            assertThat(inbox().single().int("read")).isEqualTo(1)
            assertThat(posted).isEmpty()
        } finally {
            Locale.setDefault(before)
        }
    }

    @Test
    fun aRegionMismatchBetweenTheChatAndTheTextFailsOpen() {
        // Read in Germany, the cached national number is not the text's +1 number.
        val before = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY)
        try {
            val thread = store.threadOf(STRANGER)
            cache(NATIONAL, thread)

            receive("Big sale today only")

            assertThat(inbox().single().int("read")).isEqualTo(0)
            assertThat(posted).hasSize(1)
            assertThat(messagesList()).contains(thread)
            assertThat(spamList()).doesNotContain(thread)
        } finally {
            Locale.setDefault(before)
        }
    }

    @Test
    fun theSafetyCheckLeavesTheCachedChatAndItsTextUnread() {
        val thread = store.threadOf(STRANGER)
        cache(STRANGER, thread)
        store.afterInsert = { app.conversationsDB.deleteSpamThread(thread) }

        receive("Big sale today only")

        val id = (inbox().single()["_id"] as String).toLong()
        assertThat(offMainThread { app.messagesDB.getMessage(id, false) }!!.read).isFalse()
        assertThat(offMainThread { app.conversationsDB.getConversationWithThreadId(thread) }!!.read).isFalse()
        assertThat(messagesList()).contains(thread)
        assertThat(posted).hasSize(1)
    }

    // ---- Reviewer: a text judged Spam is never forwarded ----

    @Test
    fun aSpamTextIsNeverForwardedButAMessagesTextIs() {
        forwardTo(TARGET)

        receive("Big sale today only")
        assertThat(forwards()).isEmpty()

        settings { it.copy(filterOn = false) }
        receive("See you at noon", at = now + 60_000L)
        assertThat(forwards()).containsExactly(TARGET)
    }

    @Test
    fun aSpamTextAnnouncedByTheSafetyCheckIsNotForwarded() {
        forwardTo(TARGET)
        store.afterInsert = { app.conversationsDB.deleteSpamThread(store.threadOf(STRANGER)) }

        receive("Big sale today only")

        assertThat(posted).hasSize(1)
        assertThat(forwards()).isEmpty()
    }

    @Test
    fun aSpamTextWithoutAThreadIdFailsOpenAndIsNotForwarded() {
        forwardTo(TARGET)
        store.failThreadIds = true

        receive("Big sale today only")

        assertThat(inbox().single().int("read")).isEqualTo(0)
        assertThat(posted).hasSize(1)
        assertThat(forwards()).isEmpty()
    }

    @Test
    fun aSpamRowThatCannotBeWrittenFailsOpenAndIsNotForwarded() {
        forwardTo(TARGET)
        val sql = MessagesDatabase.getInstance(app).openHelper
        offMainThread {
            sql.writableDatabase.execSQL(
                "CREATE TRIGGER refuse_spam BEFORE INSERT ON spam_threads BEGIN SELECT RAISE(ABORT, 'refused'); END"
            )
        }
        try {
            receive("Big sale today only")

            assertThat(inbox().single().int("read")).isEqualTo(0)
            assertThat(posted).hasSize(1)
            assertThat(forwards()).isEmpty()
        } finally {
            offMainThread { sql.writableDatabase.execSQL("DROP TRIGGER IF EXISTS refuse_spam") }
        }
    }

    // ---- Archive ----

    @Test
    fun aSpamTextLeavesItsChatArchivedAMessagesTextDoesNot() {
        app.config.isArchiveAvailable = true
        app.config.keepConversationsArchived = false
        val spamThread = store.threadOf(STRANGER)
        cache(STRANGER, spamThread, archived = true)
        val codeThread = store.threadOf(OTHER)
        cache(OTHER, codeThread, archived = true)

        receive("Big sale today only")
        receive(CODE, address = OTHER)

        assertThat(offMainThread { app.conversationsDB.getConversationWithThreadId(spamThread) }!!.isArchived).isTrue()
        assertThat(offMainThread { app.conversationsDB.getConversationWithThreadId(codeThread) }!!.isArchived).isFalse()
    }

    // ---- Reviewer: an expired queued text keeps its sender's place ----

    @Test
    fun anExpiredTextOfABlockedSenderIsStoredSilentlyInSpam() {
        runBlocking { repo.addRule(SpamRule(kind = ListKind.BLOCK, type = RuleType.NUMBER, pattern = STRANGER)) }
        val old = now - PendingSmsPolicy.MAX_AGE_MS - 60_000L
        offMainThread { db.spamDao().insertPending(message("Big sale today only", at = old)) }

        offMainThread { PendingSmsQueue.replay(app, now) }

        assertThat(inbox().single().int("read")).isEqualTo(1)
        assertThat(spamRow()!!.cause).isEqualTo(SpamCause.BLOCKED)
        assertThat(posted).isEmpty()
        assertThat(db.spamDao().getPending()).isEmpty()
    }

    // ---- D-2: the replay never re-announces a Messages text ----

    @Test
    fun theReplayLeavesAMessagesTextTheUserHasRead() {
        // A Messages text, read since; its pending row outlived the insert. It was never written silently.
        store.addInbox(STRANGER, "See you at noon", now, store.threadOf(STRANGER), read = 1)
        settings { it.copy(filterOn = false) }
        offMainThread {
            db.spamDao().insertPending(message("See you at noon", createdAt = now - PendingSmsPolicy.GRACE_MS - 1))
        }

        offMainThread { PendingSmsQueue.replay(app, now) }

        assertThat(inbox().single().int("read")).isEqualTo(1)
        assertThat(posted).isEmpty()
        assertThat(db.spamDao().getPending()).isEmpty()
    }

    // ---- R2 and B2-14: the real order of the history check ----

    @Test
    fun beforeTheFirstRunEndsTheSenderIsCheckedFirstAndAnOldReplyKeepsItInMessages() {
        // R2: the first SMS after an update, from a sender with an old sent text.
        firstRun(FirstRunState())
        val thread = store.threadOf(STRANGER)
        cache(STRANGER, thread)
        ArrivalFacts.checkSender = { _, address, _ ->
            checkedSenders += address
            runBlocking { repo.joinGroup(AllowGroup.WROTE, address, MemberSource.HISTORY) }
        }

        receive("Big sale today only")

        assertThat(checkedSenders).containsExactly(STRANGER)
        assertThat(inbox().single().int("read")).isEqualTo(0)
        assertThat(posted).hasSize(1)
        assertThat(spamList()).doesNotContain(thread)
    }

    @Test
    fun aChatNotCachedYetHasItsSenderCheckedBeforeTheVerdict() {
        receive("Big sale today only")

        assertThat(checkedSenders).containsExactly(STRANGER)
    }

    @Test
    fun aCachedChatAfterTheFirstRunIsNotCheckedAgain() {
        cache(STRANGER, store.threadOf(STRANGER))

        receive("Big sale today only")

        assertThat(checkedSenders).isEmpty()
        assertThat(spamRow()).isNotNull()
    }

    // ---- D-4: a number the user wrote to is put back on arrival ----

    @Test
    fun aTextFromANumberTheUserWroteToJoinsTheGroupAndGoesToMessages() {
        cache(STRANGER, store.threadOf(STRANGER))
        wroteAnswer = ContactLookup.FOUND

        receive("Big sale today only")

        val member = repo.groupMembership.members().single()
        assertThat(member.group).isEqualTo(AllowGroup.WROTE)
        assertThat(member.source).isEqualTo(MemberSource.HISTORY)
        assertThat(inbox().single().int("read")).isEqualTo(0)
        assertThat(posted).hasSize(1)
        assertThat(spamRow()).isNull()
    }

    // ---- D-7: people the user wrote to are known for the notification ----

    @Test
    fun aMemberOfPeopleYouWroteToIsKnownACodeSenderIsNot() {
        runBlocking { repo.joinGroup(AllowGroup.WROTE, STRANGER, MemberSource.LIVE) }
        runBlocking { repo.joinGroup(AllowGroup.CODE, OTHER, MemberSource.LIVE) }
        val member = Judgement(SenderVerdict.Messages(DecisionReason.GROUP_MEMBER))

        val wrote = offMainThread { ArrivalFacts.messagesDecision(app, STRANGER, member) }
        val code = offMainThread { ArrivalFacts.messagesDecision(app, OTHER, member) }

        assertThat(wrote!!.reason).isEqualTo(DecisionReason.KNOWN_CONVERSATION)
        assertThat(code!!.reason).isEqualTo(DecisionReason.GROUP_MEMBER)
    }

    // ---- R1 cases with facts ----

    @Test
    fun aFactThatCannotBeWrittenEndsUnreadAndAnnounced() {
        offMainThread {
            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER refuse_member BEFORE INSERT ON allow_group_members " +
                    "BEGIN SELECT RAISE(ABORT, 'refused'); END"
            )
        }

        receive(CODE)

        assertThat(inbox().single().int("read")).isEqualTo(0)
        assertThat(posted).hasSize(1)
        assertThat(repo.groupMembership.members()).isEmpty()
        assertThat(spamRow()).isNull()
    }

    @Test
    fun aWordOfTheOldFossifyListBlocksTheSenderSilently() {
        legacyWords = listOf("casino")

        receive("Win big at the casino tonight")

        assertThat(inbox().single().int("read")).isEqualTo(1)
        assertThat(spamRow()!!.cause).isEqualTo(SpamCause.BLOCKED)
        assertThat(db.spamDao().getRules().single().note).isEqualTo(RuleNotes.BLOCKED_FROM_KEYWORD)
        assertThat(posted).isEmpty()
    }

    @Test
    fun aBlockedWordBlocksTheSenderWithNoNoticeAndCountsItsHit() {
        settings { it.copy(notifyOnBlock = true) }
        val wordId = runBlocking {
            repo.addRule(SpamRule(kind = ListKind.BLOCK, type = RuleType.KEYWORD, pattern = "casino"))
        }

        receive("Win big at the casino tonight")

        assertThat(inbox().single().int("read")).isEqualTo(1)
        assertThat(spamRow()!!.cause).isEqualTo(SpamCause.BLOCKED)
        assertThat(posted).isEmpty()
        assertThat(db.spamDao().getRules().single { it.id == wordId }.hitCount).isEqualTo(1)
    }

    // ---- The notice on the lock screen ----

    @Test
    fun theQuietNoticeShowsNoSenderOrTextOnTheLockScreen() {
        settings { it.copy(notifyOnBlock = true) }

        receive("Big sale today only")

        val locked = posted.single().publicVersion!!
        val shown = locked.extras.keySet().joinToString(" ") { locked.extras.get(it).toString() }
        assertThat(locked.extras.getCharSequence(android.app.Notification.EXTRA_TITLE).toString())
            .isEqualTo(app.getString(com.glooboo.umessage.R.string.spam_engine_blocked_public_title))
        assertThat(shown).doesNotContain(STRANGER)
        assertThat(shown).doesNotContain("Big sale")
    }

    // ---- Clean-ups wait for the first run ----

    @Test
    fun theReceiversPurgeWaitsUntilTheFirstSortIsPublished() {
        val purge = app.getSharedPreferences("spamless_spam_settings", Context.MODE_PRIVATE)
        firstRun(FirstRunState())

        offMainThread { SmsReceiver().housekeeping(app) }
        assertThat(purge.getLong("last_purge_at", 0L)).isEqualTo(0L)

        firstRun(FirstRunState(phase = FirstRunPhase.DONE, sortPublished = true))
        offMainThread { SmsReceiver().housekeeping(app) }
        assertThat(purge.getLong("last_purge_at", 0L)).isGreaterThan(0L)
    }

    @Test
    fun withTheFlagOffTheOldPathHoldsTheText() {
        ProviderTruthFlag.enabled = false
        SpamTestWiring.installFilter { FilterDecision.Block(DecisionReason.UNKNOWN_SENDER) }

        receive("Big sale today only")

        assertThat(store.rows()).isEmpty()
        assertThat(db.spamDao().getAllBlocked()).hasSize(1)
    }

    private companion object {
        const val STRANGER = "+15550001234"
        const val OTHER = "+15550001235"

        /** The same number as [STRANGER], written as a national US number. */
        const val NATIONAL = "5550001234"
        const val TARGET = "+15550001111"
        const val CODE = "Your verification code is 482915"
    }
}

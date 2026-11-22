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
import com.glooboo.umessage.ui.lock.LockScope
import org.fossify.commons.extensions.baseConfig
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
import java.util.Collections
import java.util.Locale

/**
 * The shared set-up of the step D receive tests ([ProviderTruthSmsTest], [ProviderTruthSmsEdgeTest]): an in-memory
 * SMS store, the real filter and repository on an in-memory spamless.db, the history check replaced by a recorder.
 * Every number is made up (555 range).
 */
abstract class ProviderTruthSmsFixture {

    @get:Rule
    val flag = ProviderTruthFlagRule(enabled = true)

    protected val app: Application = ApplicationProvider.getApplicationContext()
    protected val manager get() = app.getSystemService(NotificationManager::class.java)
    protected val posted get() = shadowOf(manager).allNotifications
    protected val realCheckSender = ArrivalFacts.checkSender
    protected val realWroteTo = ArrivalFacts.wroteTo
    protected val checkedSenders = mutableListOf<String>()
    protected var historyAnswer = true
    protected var wroteAnswer = ContactLookup.NOT_FOUND
    protected lateinit var store: SpamStore
    protected lateinit var db: SpamDatabase
    protected lateinit var repo: DefaultSpamRepository
    protected val now = System.currentTimeMillis()
    private val realUncachedLane = UncachedChats.lane

    /** R3-1: the threads whose chat the app asked to cache, recorded instead of read on a background thread. */
    protected val askedToCache: MutableList<Long> = Collections.synchronizedList(mutableListOf())

    /** The SMS provider and the thread lookup, in memory; [afterInsert] runs right after each SMS insert. */
    class SpamStore : ContentProvider() {
        private lateinit var db: SQLiteDatabase
        private val threads = HashMap<String, Long>()

        @Volatile var afterInsert: (() -> Unit)? = null

        /** How many of the next SMS inserts fail the way a broken provider does (insert returns null). */
        @Volatile var failNextInserts = 0

        /** While true the thread lookup answers nothing, so the app's thread id is 0. */
        @Volatile var failThreadIds = false

        /** While true the store changes no row (an update answers 0). */
        @Volatile var refuseUpdates = false

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
            if (uri.authority == "sms" && !refuseUpdates) db.update("sms", values, selection, args) else 0

        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
        override fun getType(uri: Uri): String? = null
    }

    protected object NoPlatform : SpamPlatform {
        override fun notifyBlocked(message: BlockedMessage) = Unit
        override fun deliverRestored(pending: PendingSmsEntity): Long? = null
        override fun deliverRestoredMms(message: BlockedMessage): Long? = null
        override fun removeRestored(id: Long, isMms: Boolean) = false
        override fun deleteMedia(names: Collection<String>) = Unit
        override fun sweepMedia(keep: Set<String>) = Unit
    }

    /** Nobody is a contact, and contacts can be read. */
    protected object Strangers : ContactChecker {
        override fun isContact(address: String) = false
        override fun lookup(address: String) = ContactLookup.NOT_FOUND
        override fun canReadContacts() = true
    }

    /** The old Fossify word list of these tests. */
    protected var legacyWords = emptyList<String>()

    protected val legacy = object : LegacyBlocklist {
        override fun isNumberBlocked(address: String) = false
        override fun blockedKeywords(): Collection<String> = legacyWords
        override fun blockUnknownNumbers() = false
    }

    @Before
    fun setUp() {
        resetSpamEngine()
        UncachedChats.lane = { _, threadId -> askedToCache += threadId }
        store = Robolectric.buildContentProvider(SpamStore::class.java)
            .create(ProviderInfo().apply { authority = "sms;mms-sms" })
            .get()
        db = SpamTestWiring.freshSpamDatabase(app)
        SpamTestWiring.resetPendingLeases()
        NotifyDue.resetForTest(app)
        ShadowRecordingSmsManager.reset()
        RemovedMembers.reset()
        UMessage.log(app).clear()
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
        UncachedChats.lane = realUncachedLane
        ArrivalFacts.checkSender = realCheckSender
        ArrivalFacts.wroteTo = realWroteTo
        firstRun(FirstRunState())
        NotifyDue.resetForTest(app)
        SpamTestWiring.uninstall()
        SpamTestWiring.clearSpamDatabase()
        ShadowRecordingSmsManager.reset()
        RemovedMembers.reset()
        resetSharedMessagesDatabase()
        resetSpamEngine()
    }

    /** SpamEngine keeps singletons made with an earlier test's Application; they are made again for this one. */
    protected fun resetSpamEngine() {
        listOf("repository", "filter", "settingsStore", "forwardPlanner", "checker").forEach { name ->
            runCatching { SpamEngine::class.java.getDeclaredField(name).apply { isAccessible = true }.set(null, null) }
        }
    }

    protected fun firstRun(state: FirstRunState) {
        FirstRunStore(app.getSharedPreferences(FirstRunStore.PREFS, Context.MODE_PRIVATE)).write(state)
        FirstRun.reload(app)
    }

    /** A chat the app has cached for [threadId], under [number]. */
    protected fun cache(number: String, threadId: Long, archived: Boolean = false) = offMainThread {
        app.conversationsDB.insertOrUpdate(
            Conversation(threadId, "Earlier", 100, true, "Chat", "", false, number, isArchived = archived)
        )
    }

    protected fun spamList() = offMainThread { app.conversationsDB.getSpamList().map { it.threadId } }

    protected fun messagesList() = offMainThread { app.conversationsDB.getMessagesList().map { it.threadId } }

    protected fun forwardTo(target: String) {
        db.forwardDao().insert(
            ForwardRuleEntity.from(
                ForwardRule(senderType = RuleType.NUMBER, senderPattern = STRANGER, targets = listOf(target))
            )
        )
    }

    protected fun forwards() = ShadowRecordingSmsManager.textSends.map { it.destination }

    protected fun inbox() = store.rows().filter { it.int("type") == Telephony.Sms.MESSAGE_TYPE_INBOX }

    protected fun receive(body: String, address: String = STRANGER, at: Long = now) = offMainThread {
        IncomingSmsProcessor.process(app, message(body, address, at))
    }

    protected fun message(body: String, address: String = STRANGER, at: Long = now, createdAt: Long = at) =
        PendingSmsEntity(
            address = address, subject = "", body = body, date = at, subscriptionId = 1,
            status = Telephony.Sms.STATUS_NONE, createdAt = createdAt,
        )

    protected fun settings(change: (SpamSettings) -> SpamSettings) =
        runBlocking { repo.updateSettings(change) }

    protected fun spamRow(address: String = STRANGER) =
        offMainThread { app.conversationsDB.getSpamThread(store.threadOf(address)) }

    protected fun blockedEvents() = runBlocking { db.spamDao().observeEventCount(SpamEventType.BLOCKED).first() }

    protected fun Map<String, Any?>.int(column: String) = (get(column) as String?)?.toInt()

    companion object {
        const val STRANGER = "+15550001234"
        const val OTHER = "+15550001235"

        /** The same number as [STRANGER], written as a national US number. */
        const val NATIONAL = "5550001234"
        const val TARGET = "+15550001111"
        const val CODE = "Your verification code is 482915"
    }
}

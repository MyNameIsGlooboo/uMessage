package com.glooboo.umessage.spam.data

import android.app.Application
import android.app.NotificationManager
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
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.helpers.ProviderTruthFlag
import com.glooboo.umessage.spam.AllowGroup
import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.FilterDecision
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamRule
import com.glooboo.umessage.spam.SpamSettings
import com.glooboo.umessage.spam.SpamThreads
import com.glooboo.umessage.spam.UMessage
import com.glooboo.umessage.spam.engine.ContactChecker
import com.glooboo.umessage.spam.engine.ContactLookup
import com.glooboo.umessage.spam.engine.DefaultSpamFilter
import com.glooboo.umessage.spam.engine.NumberNormalizer
import com.glooboo.umessage.spam.engine.SpamCause
import com.glooboo.umessage.testing.ProviderTruthFlagRule
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
    private val realHistoryCheck = ProviderTruthSms.checkHistory
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

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? = when {
            uri.authority == "mms-sms" && uri.pathSegments.firstOrNull() == "threadID" ->
                MatrixCursor(arrayOf("_id")).apply {
                    addRow(arrayOf<Any>(threadOf(uri.getQueryParameter("recipient")!!)))
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

    @Before
    fun setUp() {
        store = Robolectric.buildContentProvider(SpamStore::class.java)
            .create(ProviderInfo().apply { authority = "sms;mms-sms" })
            .get()
        db = SpamTestWiring.freshSpamDatabase(app)
        SpamTestWiring.resetPendingLeases()
        NotifyDue.resetForTest(app)
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
            groups = repo.groupMembership,
        )
        UMessage.filterFactory = { filter }
        UMessage.repositoryFactory = { repo }
        // The history check of the first run is step B's; here every sender's history is known.
        ProviderTruthSms.checkHistory = { _, _, _ -> true }
    }

    @After
    fun tearDown() {
        ProviderTruthSms.checkHistory = realHistoryCheck
        NotifyDue.resetForTest(app)
        SpamTestWiring.uninstall()
        SpamTestWiring.clearSpamDatabase()
        resetSharedMessagesDatabase()
    }

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
        ProviderTruthSms.checkHistory = { _, _, _ -> false }

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
        // The process died after the silent insert; meanwhile the chat left Spam.
        val thread = store.threadOf(STRANGER)
        store.addInbox(STRANGER, "Big sale today only", now, thread, read = 1)
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
    }
}

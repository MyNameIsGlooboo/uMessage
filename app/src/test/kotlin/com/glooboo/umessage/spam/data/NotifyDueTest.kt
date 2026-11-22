package com.glooboo.umessage.spam.data

import android.app.AlarmManager
import android.app.Application
import android.app.Notification
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
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.R
import com.glooboo.umessage.databases.MessagesDatabase
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.helpers.NotifyContext
import com.glooboo.umessage.helpers.NotifyLog
import com.glooboo.umessage.helpers.NotifyMode
import com.glooboo.umessage.helpers.NotifyResult
import com.glooboo.umessage.helpers.NotifyStage
import com.glooboo.umessage.helpers.notifyFault
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.receivers.PendingSmsRetry
import com.glooboo.umessage.receivers.PendingSmsRetryReceiver
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.FilterDecision
import com.glooboo.umessage.spam.SpamThread
import com.glooboo.umessage.spam.engine.SpamCause
import com.glooboo.umessage.testing.SpamTestWiring
import com.glooboo.umessage.testing.offMainThread
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowLog

/** N6: a stored message whose notification was never posted is notified once by the replay, and only then. */
@RunWith(AndroidJUnit4::class)
class NotifyDueTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val manager get() = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val posted get() = shadowOf(manager).allNotifications
    private val now = System.currentTimeMillis()
    private lateinit var provider: DueSmsProvider
    private var decide: () -> FilterDecision = { FilterDecision.Allow(DecisionReason.NO_MATCH) }

    /** The system SMS provider and thread-id lookup, in memory, with a switch that fails the next inserts. */
    class DueSmsProvider : ContentProvider() {
        private lateinit var db: SQLiteDatabase

        /** How many of the next SMS inserts fail the way a broken provider does (insert returns null). */
        @Volatile var failNextInserts = 0

        /** Reads of one message by id throw, as a provider that cannot be read does. */
        @Volatile var failIdReads = false

        override fun onCreate(): Boolean {
            db = SQLiteDatabase.create(null)
            db.execSQL(
                "CREATE TABLE sms (_id INTEGER PRIMARY KEY AUTOINCREMENT, address TEXT, subject TEXT, body TEXT, " +
                    "date INTEGER, date_sent INTEGER DEFAULT 0, read INTEGER DEFAULT 0, thread_id INTEGER, " +
                    "type INTEGER, sub_id INTEGER DEFAULT -1, status INTEGER DEFAULT -1)"
            )
            return true
        }

        fun addSms(
            date: Long,
            read: Int = 0,
            type: Int = Telephony.Sms.MESSAGE_TYPE_INBOX,
            body: String = BODY,
        ): Long =
            db.insert("sms", null, ContentValues().apply {
                put("address", ADDRESS)
                put("body", body)
                put("date", date)
                put("read", read)
                put("thread_id", THREAD)
                put("type", type)
            })

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? = when {
            uri.authority == "mms-sms" && uri.pathSegments.firstOrNull() == "threadID" ->
                MatrixCursor(arrayOf("_id")).apply { addRow(arrayOf<Any>(THREAD)) }
            uri.authority == "sms" -> {
                if (failIdReads && selection?.startsWith(Telephony.Sms._ID) == true) error("provider broke")
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
            return if (id > 0) Uri.withAppendedPath(Telephony.Sms.CONTENT_URI, id.toString()) else null
        }

        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
        override fun getType(uri: Uri): String? = null
    }

    @Before
    fun setUp() {
        provider = Robolectric.buildContentProvider(DueSmsProvider::class.java)
            .create(ProviderInfo().apply { authority = "sms;mms-sms" })
            .get()
        SpamTestWiring.freshSpamDatabase(app)
        SpamTestWiring.resetPendingLeases()
        SpamTestWiring.installFilter { decide() }
        NotifyDue.resetForTest(app)
    }

    @After
    fun tearDown() {
        notifyFault = null
        NotifyDue.resetForTest(app)
        SpamTestWiring.uninstall()
        SpamTestWiring.clearSpamDatabase()
        resetSharedMessagesDatabase()
    }

    private fun deliver(notify: NotifyContext, read: Int = 0): Long = offMainThread {
        app.deliverIncomingSms(
            address = ADDRESS, subject = "", body = BODY, date = now, subscriptionId = -1,
            status = Telephony.Sms.STATUS_NONE, notify = notify, read = read,
        )
    }

    /** The retry alarms booked so far. */
    private fun retryAlarms() =
        shadowOf(app.getSystemService(Context.ALARM_SERVICE) as AlarmManager).scheduledAlarms.filter {
            shadowOf(it.operation).savedIntent.component?.className == PendingSmsRetryReceiver::class.java.name
        }

    private fun ShadowAlarmManager.ScheduledAlarm.attempt() =
        shadowOf(operation).savedIntent.getIntExtra(PendingSmsRetryReceiver.EXTRA_ATTEMPT, -1)

    private fun earlierPost(tag: String? = null) {
        val shown = NotificationCompat.Builder(app, "test").setSmallIcon(R.drawable.ic_messenger)
            .setContentTitle(EARLIER).build()
        manager.notify(tag, THREAD.hashCode(), shown)
    }

    private fun Notification.title() = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()

    /** A marker as a process that died after the insert left it. */
    private fun markDead(id: Long, date: Long = now, notify: NotifyContext = NotifyContext(), createdAt: Long = now) =
        NotifyDue.mark(app, id, date, notify, live = false, now = createdAt)

    private fun replay(at: Long = now + 1_000L) = offMainThread { NotifyDue.replay(app, at) }

    private fun pending() = NotifyDue.pendingIds(app)

    /** The chat [THREAD] is in Spam for [sender] since [since] (not cached yet, so its row names the sender). */
    private fun spamRow(since: Long, sender: String = ADDRESS) = offMainThread {
        app.conversationsDB.insertSpamThread(SpamThread(THREAD, sender, SpamCause.UNKNOWN, spamSince = since))
    }

    /** The chat [THREAD] is cached with the number [number]. */
    private fun cachedChat(number: String = ADDRESS) = offMainThread {
        app.conversationsDB.insertOrUpdate(
            Conversation(
                threadId = THREAD, snippet = "", date = 0, read = false, title = number, photoUri = "",
                isGroupConversation = false, phoneNumber = number,
            ),
        )
    }

    /** The results this test's replays logged under uMessageNotify. */
    private fun loggedResults() = ShadowLog.getLogsForTag(NotifyLog.TAG).mapNotNull {
        Regex(" r=([A-Z_]+)").find(it.msg)?.groupValues?.get(1)
    }

    @Test
    fun deliveryMarksThenClearsTheMarker() {
        var duringPost: Set<Long> = emptySet()
        notifyFault = { if (it == NotifyStage.BUILD) duringPost = NotifyDue.pendingIds(app) }

        val id = deliver(NotifyContext())

        assertThat(duringPost).containsExactly(id)
        assertThat(posted).hasSize(1)
        assertThat(pending()).isEmpty()
    }

    @Test
    fun aDeliveryBooksTheRetryAlarmRightAfterTheMarker() {
        // R2: a process that died before the post would leave the marker to this alarm.
        var alarmsDuringPost = -1
        notifyFault = { if (it == NotifyStage.BUILD) alarmsDuringPost = retryAlarms().size }

        deliver(NotifyContext())

        assertThat(alarmsDuringPost).isEqualTo(1)
        assertThat(posted).hasSize(1)
        assertThat(pending()).isEmpty()
    }

    @Test
    fun withNoRetryBookedTheMarkerBooksTheFirstAttempt() {
        deliver(NotifyContext())

        assertThat(retryAlarms().map { it.attempt() }).containsExactly(0)
    }

    @Test
    fun theMarkerKeepsAQueueRetryThatIsAlreadyBooked() {
        // A queued SMS waits for attempt 3; the marker must not set it back to attempt 0 and a new 6-attempt chain.
        PendingSmsRetry.schedule(app, attempt = 3)
        val booked = retryAlarms().single().triggerAtTime

        deliver(NotifyContext())

        val alarm = retryAlarms().single()
        assertThat(alarm.attempt()).isEqualTo(3)
        assertThat(alarm.triggerAtTime).isEqualTo(booked)
    }

    @Test
    fun aThrowAfterTheMarkerLeavesItForTheReplay() {
        // R4: the id leaves the in-flight set, the marker stays, and the next replay posts the message once.
        notifyFault = { if (it == NotifyStage.DELIVER) error("test fault") }

        val thrown = runCatching { deliver(NotifyContext()) }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(IllegalStateException::class.java)
        assertThat(posted).isEmpty()
        assertThat(pending()).hasSize(1)

        notifyFault = null
        assertThat(replay()).isEqualTo(0)
        assertThat(posted).hasSize(1)
        assertThat(pending()).isEmpty()
        assertThat(replay()).isEqualTo(0)
        assertThat(posted).hasSize(1)
    }

    @Test
    fun bothPostsFailingKeepsTheMarkerAndBooksTheRetry() {
        notifyFault = { if (it == NotifyStage.BUILD || it == NotifyStage.PLAIN) error("test fault") }

        val id = deliver(NotifyContext())

        assertThat(posted).isEmpty()
        assertThat(pending()).containsExactly(id)
        assertThat(retryAlarms()).hasSize(1)

        // Settled as FAILED, so no longer in flight: the next replay posts it.
        notifyFault = null
        assertThat(replay()).isEqualTo(0)
        assertThat(posted).hasSize(1)
        assertThat(pending()).isEmpty()
    }

    @Test
    fun aMessageAlreadyReadAtDeliveryClearsTheMarker() {
        deliver(NotifyContext(), read = 1)

        assertThat(posted).isEmpty()
        assertThat(pending()).isEmpty()
    }

    @Test
    fun modeNoneLeavesNoMarkerAndNoNotification() {
        var duringPost: Set<Long>? = null
        notifyFault = { duringPost = NotifyDue.pendingIds(app) }

        deliver(NotifyContext.NONE)

        assertThat(duringPost).isNull()
        assertThat(pending()).isEmpty()
        assertThat(posted).isEmpty()
    }

    @Test
    fun replayPostsOnceForAStoredUnreadMessage() {
        val id = provider.addSms(now)
        markDead(id)

        assertThat(replay()).isEqualTo(0)

        assertThat(posted).hasSize(1)
        assertThat(shadowOf(manager).getNotification(THREAD.hashCode())).isNotNull()
        assertThat(pending()).isEmpty()
    }

    @Test
    fun secondReplayPostsNothing() {
        markDead(provider.addSms(now))
        replay()
        manager.cancelAll()

        replay()

        assertThat(posted).isEmpty()
    }

    @Test
    fun replaySkipsAReadMessage() {
        markDead(provider.addSms(now, read = 1))

        replay()

        assertThat(posted).isEmpty()
        assertThat(pending()).isEmpty()
    }

    @Test
    fun replaySkipsADeletedMessage() {
        markDead(MISSING_ID)

        replay()

        assertThat(posted).isEmpty()
        assertThat(pending()).isEmpty()
    }

    @Test
    fun replaySkipsARowWithAnotherDate() {
        markDead(provider.addSms(now - 5_000L), date = now)

        replay()

        assertThat(posted).isEmpty()
        assertThat(pending()).isEmpty()
    }

    @Test
    fun replaySkipsWhenTheChatAlreadyShowsANewerNotification() {
        // Created at 0: whatever clock the notification manager stamps posts with, the one below is newer.
        markDead(provider.addSms(now), createdAt = 0L)
        val shown = NotificationCompat.Builder(app, "test").setSmallIcon(R.drawable.ic_messenger)
            .setContentTitle(EARLIER).build()
        manager.notify(THREAD.hashCode(), shown)

        replay(at = 1_000L)

        val n = posted.single()
        assertThat(n.extras.getCharSequence(Notification.EXTRA_TITLE).toString()).isEqualTo(EARLIER)
        assertThat(pending()).isEmpty()
    }

    @Test
    fun anOlderNotificationOfTheChatDoesNotStopTheReplay() {
        // The marker is newer than whatever clock the notification manager stamps the earlier post with.
        val createdAt = now + HOUR_MS
        markDead(provider.addSms(now), createdAt = createdAt)
        earlierPost()

        replay(at = createdAt + 1_000L)

        assertThat(posted.single().title()).isNotEqualTo(EARLIER)
        assertThat(pending()).isEmpty()
    }

    @Test
    fun aTaggedNoticeWithTheSameIdDoesNotStopTheReplay() {
        // R8: BlockedNotifier and other notices are tagged and share the id space.
        markDead(provider.addSms(now), createdAt = 0L)
        earlierPost(tag = "notice")

        replay(at = 1_000L)

        assertThat(posted).hasSize(2)
        assertThat(pending()).isEmpty()
    }

    @Test
    fun aSwipedNotificationDoesNotComeBackOnTheNextSms() {
        // J1: the user swipes the notification away and leaves the text unread; the next SMS runs the replay.
        deliver(NotifyContext())
        assertThat(posted).hasSize(1)
        manager.cancelAll()

        offMainThread { PendingSmsQueue.replay(app, now + 1_000L) }

        assertThat(posted).isEmpty()
        assertThat(pending()).isEmpty()
    }

    @Test
    fun aReplayedNotificationThatWasSwipedDoesNotComeBack() {
        // J1: a failed post is still replayed once (the hotfix), and only once.
        notifyFault = { if (it == NotifyStage.BUILD || it == NotifyStage.PLAIN) error("test fault") }
        deliver(NotifyContext())
        notifyFault = null
        replay()
        assertThat(posted).hasSize(1)
        manager.cancelAll()

        replay(at = now + 2_000L)

        assertThat(posted).isEmpty()
        assertThat(pending()).isEmpty()
    }

    @Test
    fun replaySkipsAChatThatEnteredSpamAfterTheText() {
        // J1, provider truth: the chat moved to Spam after the text came; a loud notification is not owed any more.
        spamRow(since = now + 1)
        markDead(provider.addSms(now), createdAt = now)

        replay()

        assertThat(posted).isEmpty()
        assertThat(pending()).isEmpty()
        assertThat(loggedResults()).contains("SKIPPED_SPAM")
    }

    @Test
    fun aCachedChatThatEnteredSpamAfterTheTextIsSkipped() {
        // The isInSpam branch: the chat is cached and its row names the chat's number.
        cachedChat()
        spamRow(since = now + 1)
        markDead(provider.addSms(now), createdAt = now)

        replay()

        assertThat(posted).isEmpty()
        assertThat(loggedResults()).contains("SKIPPED_SPAM")
    }

    @Test
    fun aCachedChatWhoseRowNamesAnotherNumberIsStillReplayed() {
        // The row does not count for the chat (S9), so the chat is in Messages.
        cachedChat()
        spamRow(since = now + 1, sender = OTHER_ADDRESS)
        markDead(provider.addSms(now), createdAt = now)

        replay()

        assertThat(posted).hasSize(1)
    }

    @Test
    fun aTextAnnouncedInAChatAlreadyInSpamIsStillReplayed() {
        // J1-1: an unknown sender's chat is in Spam, but its code goes to Messages, loud. The sorter removes the row
        // only later, so a marker replayed before that (bare insert, app start) must still post.
        spamRow(since = now - 1)
        markDead(provider.addSms(now, body = CODE_BODY), createdAt = now)

        replay()

        assertThat(posted).hasSize(1)
        assertThat(pending()).isEmpty()
    }

    @Test
    fun aChatThatEnteredSpamAtTheMarkersTimeIsStillReplayed() {
        spamRow(since = now)
        markDead(provider.addSms(now), createdAt = now)

        replay()

        assertThat(posted).hasSize(1)
    }

    @Test
    fun aSpamRowThatCannotBeReadStillPosts() {
        // Fail open: a row the store cannot read back (here an unknown cause) is an error, and an error posts.
        offMainThread {
            MessagesDatabase.getInstance(app).openHelper.writableDatabase.execSQL(
                "INSERT INTO spam_threads (thread_id, sender, cause, unread, spam_since) VALUES (?, ?, 'BROKEN', 0, ?)",
                arrayOf<Any>(THREAD, ADDRESS, now + 1),
            )
        }
        markDead(provider.addSms(now), createdAt = now)

        replay()

        assertThat(posted).hasSize(1)
    }

    @Test
    fun aSpamRowOfAnotherSenderDoesNotStopTheReplay() {
        spamRow(since = now + 1, sender = OTHER_ADDRESS)
        markDead(provider.addSms(now), createdAt = now)

        replay()

        assertThat(posted).hasSize(1)
    }

    @Test
    fun anUnreadableProviderKeepsTheMarkerWaiting() {
        val id = provider.addSms(now)
        markDead(id)
        provider.failIdReads = true

        assertThat(replay()).isEqualTo(1)
        assertThat(offMainThread { PendingSmsQueue.replay(app, now + 1_000L) }).isGreaterThan(0)

        assertThat(posted).isEmpty()
        assertThat(pending()).containsExactly(id)
    }

    @Test
    fun replaySkipsASenderBlockedByARule() {
        decide = { FilterDecision.Block(DecisionReason.BLOCK_RULE, 5L) }
        markDead(provider.addSms(now))

        replay()

        assertThat(posted).isEmpty()
        assertThat(pending()).isEmpty()
    }

    @Test
    fun replayStillPostsWhenTheFilterSaysUnknownOrFails() {
        decide = { FilterDecision.Block(DecisionReason.UNKNOWN_SENDER) }
        markDead(provider.addSms(now))
        replay()
        assertThat(posted).hasSize(1)

        manager.cancelAll()
        decide = { error("filter broke") }
        markDead(provider.addSms(now))
        replay()
        assertThat(posted).hasSize(1)
        assertThat(pending()).isEmpty()
    }

    @Test
    fun replayLeavesAMessageThatIsBeingHandledNow() {
        val id = provider.addSms(now)
        NotifyDue.mark(app, id, now, NotifyContext(), live = true, now = now)

        replay()

        assertThat(posted).isEmpty()
        assertThat(pending()).containsExactly(id)

        NotifyDue.settle(app, id, NotifyResult.POSTED)
        assertThat(pending()).isEmpty()
    }

    @Test
    fun aFailedPostKeepsTheMarker() {
        val id = provider.addSms(now)
        NotifyDue.mark(app, id, now, NotifyContext(), live = true, now = now)

        NotifyDue.settle(app, id, NotifyResult.FAILED)

        assertThat(pending()).containsExactly(id)
        replay()
        assertThat(posted).hasSize(1)
        assertThat(pending()).isEmpty()
    }

    @Test
    fun anOldMarkerIsDropped() {
        markDead(provider.addSms(now), createdAt = now - EIGHT_DAYS_MS)

        replay(at = now)

        assertThat(posted).isEmpty()
        assertThat(pending()).isEmpty()
    }

    @Test
    fun replayKeepsTheSilentModeOfTheMarker() {
        markDead(provider.addSms(now), notify = NotifyContext(NotifyMode.SILENT, DecisionReason.UNKNOWN_SENDER))

        replay()

        assertThat(posted.single().channelId).isEqualTo("unknown_senders_silent")
    }

    @Test
    fun markerHoldsNoContent() {
        markDead(provider.addSms(now), notify = NotifyContext(NotifyMode.NORMAL, DecisionReason.CONTACT))

        val raw = app.getSharedPreferences("notify_due", Context.MODE_PRIVATE).all.values.joinToString(" ")

        assertThat(raw).isNotEmpty()
        assertThat(raw).doesNotContain(ADDRESS)
        assertThat(raw).doesNotContain(BODY)
    }

    @Test
    fun bareInsertPathIsNotifiedByTheNextReplay() {
        // The first insert (deliverIncomingSms) fails, the bare one in insertRaw takes it.
        provider.failNextInserts = 1
        val message = PendingSmsEntity(
            address = ADDRESS, subject = "", body = BODY, date = now, subscriptionId = -1,
            status = Telephony.Sms.STATUS_NONE, createdAt = now,
        )

        val id = offMainThread { IncomingSmsProcessor.deliver(app, message, NotifyContext()) }

        assertThat(id).isGreaterThan(0L)
        assertThat(pending()).containsExactly(id)
        assertThat(posted).isEmpty()

        offMainThread { PendingSmsQueue.replay(app) }

        assertThat(posted).hasSize(1)
        assertThat(pending()).isEmpty()
    }

    @Test
    fun aQueueRowStoredByTheBareInsertIsNotifiedInTheSamePass() {
        // R5: the row's first insert fails, the bare one takes it and leaves a marker during this replay.
        val row = PendingSmsEntity(
            address = ADDRESS, subject = "", body = BODY, date = now, subscriptionId = -1,
            status = Telephony.Sms.STATUS_NONE, createdAt = now - PendingSmsPolicy.GRACE_MS - 1_000L,
        )
        offMainThread { SpamDatabase.getInstance(app).spamDao().insertPending(row) }
        provider.failNextInserts = 1

        val waiting = offMainThread { PendingSmsQueue.replay(app, now) }

        assertThat(waiting).isEqualTo(0)
        assertThat(posted).hasSize(1)
        assertThat(pending()).isEmpty()
    }

    @Test
    fun queueReplayAlsoReplaysMarkers() {
        markDead(provider.addSms(now))

        val waiting = offMainThread { PendingSmsQueue.replay(app, now + 1_000L) }

        assertThat(waiting).isEqualTo(0)
        assertThat(posted).hasSize(1)
    }

    private companion object {
        const val THREAD = 12L
        const val ADDRESS = "+15550001234"
        const val OTHER_ADDRESS = "PROMO"
        const val CODE_BODY = "Your login code is 482913"
        const val BODY = "See you at noon"
        const val EARLIER = "Earlier post"
        const val MISSING_ID = 999L
        const val EIGHT_DAYS_MS = 8L * 24 * 60 * 60 * 1000
        const val HOUR_MS = 60L * 60 * 1000
    }
}

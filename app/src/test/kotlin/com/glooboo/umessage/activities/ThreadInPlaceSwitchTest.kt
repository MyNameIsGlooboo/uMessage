package com.glooboo.umessage.activities

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Looper
import android.provider.ContactsContract
import android.provider.Telephony
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.extensions.messagesDB
import com.glooboo.umessage.helpers.HiddenChatsSession
import com.glooboo.umessage.helpers.THREAD_ID
import com.glooboo.umessage.helpers.THREAD_NUMBER
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.models.Events
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.models.MessageKey
import com.glooboo.umessage.testing.FakeSmsMmsProvider
import com.glooboo.umessage.testing.joinAppBackgroundThreads
import com.glooboo.umessage.testing.offMainThread
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.glooboo.umessage.ui.thread.ThreadMessagesRepository
import com.glooboo.umessage.ui.thread.ThreadRecipients
import com.glooboo.umessage.ui.thread.ThreadViewModel
import com.google.common.truth.Truth.assertThat
import org.fossify.commons.models.PhoneNumber
import org.fossify.commons.models.SimpleContact
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowLooper
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * K2-2, K2-3 (M-UI3): a chat loaded in place (a notification tapped while another chat is open) is not touched by work
 * the old chat began before it. Each test holds one step of the old chat's work inside a provider query, opens the
 * other chat, lets the held step go on, waits for its thread to end, and checks that the chat on screen is still the
 * new one, as its own load left it.
 */
@RunWith(AndroidJUnit4::class)
class ThreadInPlaceSwitchTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private var scenario: ActivityScenario<ThreadActivity>? = null

    @Before
    fun setUp() {
        HiddenChatsSession.reset()
        SwitchHolds.clear()
        shadowOf(app).grantPermissions(Manifest.permission.READ_PHONE_STATE)
        Robolectric.setupContentProvider(SwitchSmsProvider::class.java, Telephony.Sms.CONTENT_URI.authority)
        FakeSmsMmsProvider.installMms()
        Robolectric.setupContentProvider(SwitchThreadsProvider::class.java, MMS_SMS)
    }

    @After
    fun tearDown() {
        SwitchHolds.releaseAll()
        runCatching { scenario?.close() }
        joinAppBackgroundThreads()
        SwitchHolds.clear()
        ShadowAlarmManager.reset()
        HiddenChatsSession.reset()
        resetSharedMessagesDatabase()
    }

    /** K2-3: the old chat's provider page arrives after the new chat loaded; title, people and messages stay new. */
    @Test
    fun theOldChatsPageDoesNotReachTheNewChat() {
        receiveBoth()
        val hold = SwitchHolds.add(SwitchKind.SMS, "setupThread") { it == listOf("$ONE_THREAD") }
        open(chat(ONE_THREAD, ONE))
        assertThat(idleUntil { hold.isHeld }).isTrue()

        switchTo(chat(SHOP_THREAD, SHOP))
        assertThat(idleUntil { chatTwoShows() }).isTrue()
        hold.releaseAndJoin()
        idleMain()

        assertThat(vm().title).isEqualTo(SHOP)
        assertThat(shownBodies()).containsExactly(SHOP_TEXT)
        assertThat(repository().current.map { it.body }).containsExactly(SHOP_TEXT)
        assertThat(field<ThreadRecipients>("recipients").addresses).containsExactly(SHOP)
        assertThat(vm().threadId).isEqualTo(SHOP_THREAD)
    }

    /**
     * K2-2 (3): the old chat had no people yet when a notification opened another chat (no number in that intent).
     * The old load must not read the new intent, find no number and close the chat the user just opened.
     */
    @Test
    fun anOldLoadDoesNotCloseTheChatANotificationOpened() {
        receiveBoth()
        val old = SwitchHolds.add(SwitchKind.PARTICIPANTS, "setupThread", "setupParticipants") {
            it == listOf("$ONE_THREAD")
        }
        // The new chat's own load is held too, so its people are still unknown when the old load goes on.
        val next = SwitchHolds.add(SwitchKind.PARTICIPANTS, "setupCachedMessages", "setupParticipants") {
            it == listOf("$SHOP_THREAD")
        }
        open(chat(ONE_THREAD))
        assertThat(idleUntil { old.isHeld }).isTrue()

        switchTo(chat(SHOP_THREAD))
        assertThat(idleUntil { next.isHeld }).isTrue()
        old.releaseAndJoin()
        idleMain()

        assertThat(activity().isFinishing).isFalse()
        next.releaseAndJoin()
    }

    /** K2-2 (2): scheduled messages the old chat adopts while the new one loads stay out of the new chat's list. */
    @Test
    fun scheduledMessagesAdoptedForTheOldChatStayOutOfTheNewOne() {
        receiveBoth()
        storeWaitingScheduledMessage()
        val hold = SwitchHolds.add(SwitchKind.THREAD_ID, "adoptWaitingScheduledMessages") { ONE in it }
        open(chat(ONE_THREAD, ONE))
        assertThat(idleUntil { hold.isHeld }).isTrue()

        switchTo(chat(SHOP_THREAD, SHOP))
        assertThat(idleUntil { chatTwoShows() }).isTrue()
        hold.releaseAndJoin()
        idleMain()

        assertThat(repository().current.map { it.id }).doesNotContain(WAITING_ID)
        assertThat(repository().current.map { it.body }).containsExactly(SHOP_TEXT)
    }

    /**
     * K2-2 (1): a refresh asked for the old chat, still waiting for the one before it, does nothing once another chat
     * was loaded in place: it reads nothing, not even the new chat's thread.
     */
    @Test
    fun aRefreshAskedForTheOldChatDoesNothingInTheNewOne() {
        receiveBoth()
        open(chat(ONE_THREAD, ONE))
        assertThat(idleUntil { vm().title == ONE && shownBodies() == listOf(ONE_TEXT) }).isTrue()
        val running = SwitchHolds.add(SwitchKind.SMS, "refreshMessagesLocked") { it == listOf("$ONE_THREAD") }
        val screen = activity()
        val first = Thread({ screen.refreshMessages(Events.RefreshMessages()) }, RUNNING_REFRESH).apply { start() }
        assertThat(idleUntil { running.isHeld }).isTrue()
        val queued = Thread({ screen.refreshMessages(Events.RefreshMessages()) }, QUEUED_REFRESH).apply { start() }
        assertThat(idleUntil { queued.state == Thread.State.BLOCKED }).isTrue()

        switchTo(chat(SHOP_THREAD, SHOP))
        assertThat(idleUntil { chatTwoShows() }).isTrue()
        running.release()
        first.join(WAIT_MS)
        queued.join(WAIT_MS)
        idleMain()

        assertThat(SwitchHolds.smsQueriesOn(QUEUED_REFRESH)).isEmpty()
        assertThat(repository().current.map { it.body }).containsExactly(SHOP_TEXT)
    }

    /** K2-2 (4): the delayed retry of a jump to a search result in the old chat does not page through the new one. */
    @Test
    fun aJumpRetryOfTheOldChatDoesNotPageTheNewOne() {
        receiveBoth()
        open(chat(ONE_THREAD, ONE))
        assertThat(idleUntil { shownBodies() == listOf(ONE_TEXT) }).isTrue()
        // Another page is loading: the jump waits and tries again a moment later. The test runs on the main thread.
        repository().loadingOlder = true
        vm().loadAndScrollTo!!.invoke(MessageKey(OLD_SEARCH_HIT, isMMS = false))

        switchTo(chat(SHOP_THREAD, SHOP))
        assertThat(idleUntil { chatTwoShows() }).isTrue()
        // A page load the retry would start is held, so a wrong retry is still seen when the assert runs.
        SwitchHolds.add(SwitchKind.SMS, "jumpToMessage") { true }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(RETRY_WAIT_MS))

        assertThat(repository().loadingOlder).isFalse()
    }

    /** K2-2 (5): a message scheduled in the old chat keeps its temporary thread out of the new chat, and is saved. */
    @Test
    fun aMessageScheduledInTheOldChatStaysOutOfTheNewOne() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS)
        Robolectric.setupContentProvider(SwitchContactsProvider::class.java, ContactsContract.AUTHORITY)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        receive(SHOP_THREAD, SHOP, SHOP_TEXT)
        open(chat(ONE_THREAD, ONE))
        assertThat(idleUntil { vm().loaded && vm().title == ONE }).isTrue()
        // The save looks up the person's photo for the temporary thread: held there.
        val hold = SwitchHolds.add(SwitchKind.CONTACTS, "sendScheduledMessage") { true }
        vm().text.setTextAndPlaceCursorAtEnd(SCHEDULED_TEXT)
        vm().scheduledMillis = System.currentTimeMillis() + HOUR_MS
        ThreadActivity::class.java.getDeclaredMethod("sendMessage").apply { isAccessible = true }.invoke(activity())
        assertThat(idleUntil { hold.isHeld }).isTrue()

        switchTo(chat(SHOP_THREAD, SHOP))
        assertThat(idleUntil { chatTwoShows() }).isTrue()
        hold.releaseAndJoin()
        idleMain()

        assertThat(field<Long>("threadId")).isEqualTo(SHOP_THREAD)
        assertThat(repository().current.none { it.isScheduled }).isTrue()
        assertThat(offMainThread { app.messagesDB.getAllScheduledMessages() }.map { it.body })
            .containsExactly(SCHEDULED_TEXT)
    }

    private fun receiveBoth() {
        receive(ONE_THREAD, ONE, ONE_TEXT)
        receive(SHOP_THREAD, SHOP, SHOP_TEXT)
    }

    private fun receive(thread: Long, address: String, body: String) {
        app.contentResolver.insert(Telephony.Sms.CONTENT_URI, ContentValues().apply {
            put("thread_id", thread)
            put("address", address)
            put("body", body)
            put("date", System.currentTimeMillis() - 60_000L)
            put("type", Telephony.Sms.MESSAGE_TYPE_INBOX)
            put("read", 1)
            put("seen", 1)
        })
    }

    /** A message scheduled for [ONE] before the chat existed: it waits in a temporary thread of its own. */
    private fun storeWaitingScheduledMessage() {
        val person = SimpleContact(1, 1, ONE, "", arrayListOf(PhoneNumber(ONE, 0, "", ONE)), ArrayList(), ArrayList())
        val message = Message(
            id = WAITING_ID, body = "Waiting", type = Telephony.Sms.MESSAGE_TYPE_QUEUED,
            status = Telephony.Sms.STATUS_NONE, participants = arrayListOf(person),
            date = ((System.currentTimeMillis() + HOUR_MS) / 1000).toInt(), read = true, threadId = TEMPORARY_THREAD,
            isMMS = false, attachment = null, senderPhoneNumber = "", senderName = "", senderPhotoUri = "",
            subscriptionId = -1, isScheduled = true,
        )
        val temporary = Conversation(
            threadId = TEMPORARY_THREAD, snippet = "Waiting", date = 1, read = true, title = ONE, photoUri = "",
            isGroupConversation = false, phoneNumber = ONE, isScheduled = true,
        )
        offMainThread {
            app.messagesDB.insertOrUpdate(message)
            app.conversationsDB.insertOrUpdate(temporary)
        }
    }

    private fun chat(thread: Long, number: String? = null): Intent =
        Intent(app, ThreadActivity::class.java).putExtra(THREAD_ID, thread).apply {
            if (number != null) putExtra(THREAD_NUMBER, number)
        }

    private fun open(intent: Intent) {
        scenario = ActivityScenario.launch(intent)
    }

    /** A notification for [intent] is tapped: the chat screen in front loads that chat in place. */
    private fun switchTo(intent: Intent) {
        scenario!!.onActivity { screen ->
            Activity::class.java.getDeclaredMethod("onNewIntent", Intent::class.java)
                .apply { isAccessible = true }
                .invoke(screen, intent)
        }
    }

    private fun activity(): ThreadActivity {
        lateinit var screen: ThreadActivity
        scenario!!.onActivity { screen = it }
        return screen
    }

    private fun vm(): ThreadViewModel = ViewModelProvider(activity())[ThreadViewModel::class.java]

    private fun repository(): ThreadMessagesRepository =
        field<Lazy<*>>("repository\$delegate").value as ThreadMessagesRepository

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(name: String): T =
        ThreadActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity()) as T

    private fun shownBodies(): List<String> = vm().items.filterIsInstance<Message>().map { it.body }

    private fun chatTwoShows(): Boolean = vm().title == SHOP && shownBodies() == listOf(SHOP_TEXT)

    private fun idleMain() {
        repeat(3) { ShadowLooper.idleMainLooper() }
    }

    /** Runs the main looper until [done] holds, or the deadline passes; the chat loads on plain background threads. */
    private fun idleUntil(done: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            if (done()) return true
            Thread.sleep(STEP_MS)
        }
        return done()
    }

    private companion object {
        const val ONE_TEXT = "See you soon"
        const val SHOP_TEXT = "Your order is ready"
        const val SCHEDULED_TEXT = "Happy birthday!"
        const val TEMPORARY_THREAD = 900L
        const val WAITING_ID = 77L
        const val OLD_SEARCH_HIT = 4242L
        const val HOUR_MS = 3_600_000L
        const val RETRY_WAIT_MS = 1_000L
        const val RUNNING_REFRESH = "refresh-running"
        const val QUEUED_REFRESH = "refresh-queued"
        const val STEP_MS = 20L
    }
}

private const val ONE = "+15550001234"
private const val SHOP = "MyShop"
private const val ONE_THREAD = 1L
private const val SHOP_THREAD = 2L
private const val OTHER_THREAD = 3L
private const val MMS_SMS = "mms-sms"
private const val WAIT_MS = 10_000L

/** The provider query a [SwitchHold] can stop in. */
internal enum class SwitchKind { SMS, PARTICIPANTS, THREAD_ID, CONTACTS }

/**
 * Holds the first [kind] query made off the main thread with every one of [frames] on its stack and arguments that
 * [matches], until [release]. A frame is a method of the chat screen, or the lambda it runs in the background.
 */
internal class SwitchHold(
    val kind: SwitchKind,
    private val frames: List<String>,
    private val matches: (List<String>) -> Boolean,
) {
    private val reached = CountDownLatch(1)
    private val released = CountDownLatch(1)
    private val taken = AtomicBoolean(false)

    @Volatile
    private var worker: Thread? = null

    val isHeld: Boolean get() = reached.count == 0L

    fun maybeHold(args: List<String>) {
        if (Looper.myLooper() == Looper.getMainLooper() || !matches(args) || !onStack()) return
        if (!taken.compareAndSet(false, true)) return
        worker = Thread.currentThread()
        reached.countDown()
        released.await(WAIT_MS, TimeUnit.MILLISECONDS)
    }

    fun release() = released.countDown()

    /** Lets the held query go on and waits until the background work that made it has ended. */
    fun releaseAndJoin() {
        release()
        worker?.join(WAIT_MS)
    }

    private fun onStack(): Boolean {
        val names = Thread.currentThread().stackTrace.map { "${it.className}.${it.methodName}" }
        return frames.all { frame -> names.any { it.contains("$frame\$") || it.endsWith(".$frame") } }
    }
}

/** The holds of the running test, and the SMS queries each thread made. */
internal object SwitchHolds {
    private val holds = CopyOnWriteArrayList<SwitchHold>()
    private val smsQueries: MutableList<Pair<String, List<String>>> = Collections.synchronizedList(mutableListOf())

    fun add(kind: SwitchKind, vararg frames: String, matches: (List<String>) -> Boolean): SwitchHold =
        SwitchHold(kind, frames.toList(), matches).also { holds += it }

    fun at(kind: SwitchKind, args: List<String>) = holds.filter { it.kind == kind }.forEach { it.maybeHold(args) }

    fun recordSms(args: List<String>) {
        smsQueries += Thread.currentThread().name to args
    }

    fun smsQueriesOn(threadName: String): List<List<String>> =
        synchronized(smsQueries) { smsQueries.filter { it.first == threadName }.map { it.second } }

    fun releaseAll() = holds.forEach { it.release() }

    fun clear() {
        holds.clear()
        smsQueries.clear()
    }
}

/** The SMS table of [FakeSmsMmsProvider], with every query recorded and open to a [SwitchHold]. */
internal class SwitchSmsProvider : FakeSmsMmsProvider() {
    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val args = selectionArgs?.toList().orEmpty()
        SwitchHolds.recordSms(args)
        SwitchHolds.at(SwitchKind.SMS, args)
        return super.query(uri, projection, selection, selectionArgs, sortOrder)
    }
}

/**
 * `mms-sms`: the thread lookup gives [ONE] thread 1 and [SHOP] thread 2; a thread's participants are never known, so
 * the chat takes its people from the intent or from its messages.
 */
internal class SwitchThreadsProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = when (uri.pathSegments.firstOrNull()) {
        "threadID" -> {
            val recipients = uri.getQueryParameters("recipient")
            SwitchHolds.at(SwitchKind.THREAD_ID, recipients)
            MatrixCursor(arrayOf("_id")).apply { addRow(arrayOf(threadOf(recipients))) }
        }
        "conversations" -> {
            SwitchHolds.at(SwitchKind.PARTICIPANTS, selectionArgs?.toList().orEmpty())
            MatrixCursor(arrayOf(Telephony.ThreadsColumns.RECIPIENT_IDS))
        }
        else -> null
    }

    private fun threadOf(recipients: List<String>): Long = when (recipients) {
        listOf(ONE) -> ONE_THREAD
        listOf(SHOP) -> SHOP_THREAD
        else -> OTHER_THREAD
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun getType(uri: Uri): String? = null
}

/** The contacts provider: knows nobody; a query is open to a [SwitchHold]. */
internal class SwitchContactsProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        SwitchHolds.at(SwitchKind.CONTACTS, emptyList())
        return null
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun getType(uri: Uri): String? = null
}

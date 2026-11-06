package com.glooboo.umessage.ui.spam

import android.app.Application
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.models.Events
import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.data.DefaultSpamRepository
import com.glooboo.umessage.spam.data.PendingSmsEntity
import com.glooboo.umessage.spam.data.SpamDatabase
import com.glooboo.umessage.spam.data.SpamPlatform
import com.glooboo.umessage.spam.data.SpamSettingsStore
import com.glooboo.umessage.spam.engine.NumberNormalizer
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.time.Duration
import java.util.Collections

/** Not spam on the Blocked screen against the real repository: the live counter and the chat list refresh. */
@RunWith(AndroidJUnit4::class)
class BlockedViewModelRestoreTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(Job() + Dispatchers.Main)
    private val events: MutableList<BlockedEvent> = Collections.synchronizedList(mutableListOf())
    private val refreshes = Collections.synchronizedList(mutableListOf<Any>())
    private lateinit var db: SpamDatabase
    private lateinit var vm: BlockedViewModel

    /** The system inbox as far as the test can tell: ids written by the restore and not taken out again. */
    private val inbox = Collections.synchronizedSet(mutableSetOf<Long>())

    private val platform = object : SpamPlatform {
        private var nextId = 100L
        override fun notifyBlocked(message: BlockedMessage) = Unit
        override fun deliverRestored(pending: PendingSmsEntity): Long? = nextId++.also { inbox += it }
        override fun removeRestored(id: Long, isMms: Boolean) = inbox.remove(id)
    }

    @Subscribe
    fun onRefresh(event: Events.RefreshConversations) {
        refreshes += event
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(app, SpamDatabase::class.java).allowMainThreadQueries().build()
        val repo = DefaultSpamRepository(platform, db, SpamSettingsStore(app), NumberNormalizer { "US" })
        runBlocking {
            repo.storeBlocked(
                BlockedMessage(address = SENDER, body = "Hi", subscriptionId = 1, isMms = false,
                    receivedAt = System.currentTimeMillis(), reason = DecisionReason.UNKNOWN_SENDER),
            )
        }
        EventBus.getDefault().register(this)
        vm = BlockedViewModel(app, repo, RestoredLog(prefs = null))
        scope.launch { vm.state.collect { } }
        scope.launch { vm.eventFlow.collect { events += it } }
        awaitUntil { vm.state.value.groups.isNotEmpty() }
    }

    @After
    fun tearDown() {
        EventBus.getDefault().unregister(this)
        scope.cancel()
        db.close()
    }

    @Test
    fun restoreRaisesTheRestoredCounterAndRefreshesTheChatList() {
        assertThat(vm.state.value.stats.restoredTotal).isEqualTo(0)
        vm.notSpam(setOf(SENDER))
        awaitUntil { vm.state.value.allowPrompt != null }
        vm.answerAllow(vm.state.value.allowPrompt!!.token, allow = false)
        awaitUntil { events.isNotEmpty() }
        vm.commitRestore((events.single() as BlockedEvent.Restored).token)

        awaitUntil { vm.state.value.stats.restoredTotal == 1 }
        awaitUntil { refreshes.isNotEmpty() }
    }

    /**
     * The owner's bug: the messages left Blocked at once but reached the inbox only after the undo window, and
     * closing the app meanwhile lost the restore. They are in the inbox before "Always allow?" is even answered.
     */
    @Test
    fun notSpamPutsTheMessageInTheInboxBeforeTheAllowAnswer() {
        vm.notSpam(setOf(SENDER))
        awaitUntil { vm.state.value.allowPrompt != null }
        awaitUntil { inbox.size == 1 }
        awaitUntil { vm.state.value.stats.restoredTotal == 1 }
        assertThat(runBlocking { db.spamDao().getAllBlocked() }).isEmpty()
        assertThat(events).isEmpty()
    }

    /** Leaving the tab cancels the snackbar unanswered; the window closes on its own and nothing runs twice. */
    @Test
    fun theUndoWindowClosesWithoutTheSnackbar() {
        vm.notSpam(setOf(SENDER))
        awaitUntil { vm.state.value.allowPrompt != null }
        vm.answerAllow(vm.state.value.allowPrompt!!.token, allow = false)
        awaitUntil { events.isNotEmpty() }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(BlockedViewModel.UNDO_WINDOW_MS + 100))

        awaitUntil { vm.state.value.stats.restoredTotal == 1 }
        // The snackbar answering late must neither restore it again nor undo it.
        val token = (events.single() as BlockedEvent.Restored).token
        vm.commitRestore(token)
        vm.undoRestore(token)
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(200)
        awaitUntil { vm.state.value.stats.restoredTotal == 1 }
        assertThat(inbox).hasSize(1)
    }

    /** Undo really undoes: out of the inbox, back in Blocked, and the restored counter goes back down. */
    @Test
    fun undoTakesTheMessageOutOfTheInboxAndBackIntoBlocked() {
        vm.notSpam(setOf(SENDER))
        awaitUntil { vm.state.value.allowPrompt != null }
        vm.answerAllow(vm.state.value.allowPrompt!!.token, allow = false)
        awaitUntil { events.isNotEmpty() }
        assertThat(inbox).hasSize(1)

        vm.undoRestore((events.single() as BlockedEvent.Restored).token)
        awaitUntil { SENDER in vm.state.value.groups.map { it.address } }
        assertThat(inbox).isEmpty()
        awaitUntil { vm.state.value.stats.restoredTotal == 0 }
        assertThat(vm.state.value.restored).isEmpty()
    }

    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            check(System.currentTimeMillis() < deadline) { "condition not met; state=${vm.state.value} events=$events" }
            Thread.sleep(10)
        }
    }

    private companion object {
        const val SENDER = "+14155550133"
    }
}

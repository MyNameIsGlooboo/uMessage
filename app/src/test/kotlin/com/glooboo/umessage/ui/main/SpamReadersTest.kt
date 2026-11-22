package com.glooboo.umessage.ui.main

import android.app.Application
import android.os.Looper
import android.provider.Telephony
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.extensions.messagesDB
import com.glooboo.umessage.helpers.HiddenChats
import com.glooboo.umessage.helpers.HiddenChatsSession
import com.glooboo.umessage.helpers.MessageFoldBackfill
import com.glooboo.umessage.helpers.ProviderTruthFlag
import com.glooboo.umessage.helpers.SpamReaders
import com.glooboo.umessage.helpers.StarredList
import com.glooboo.umessage.helpers.messageNotes
import com.glooboo.umessage.helpers.starredDB
import com.glooboo.umessage.interfaces.SearchPlace
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.models.StarredMessage
import com.glooboo.umessage.spam.SpamThread
import com.glooboo.umessage.spam.engine.SpamCause
import com.glooboo.umessage.testing.ProcessReturn
import com.glooboo.umessage.testing.ProviderTruthFlagRule
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.glooboo.umessage.ui.lock.LockScope
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.fossify.commons.extensions.appLockManager
import org.fossify.commons.extensions.baseConfig
import org.fossify.commons.helpers.AppLockManager
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * Provider truth, step H (section 5, U9): no chat in Spam is seen outside Spam. Search (messages, titles, numbers and
 * notes), the Links tile, the stars and the SIM tile leave it out; in search it is found only in the Spam section, and
 * only while the app lock does not cover Spam. With the flag off nothing changes. Fake numbers only.
 */
@RunWith(AndroidJUnit4::class)
class SpamReadersTest {

    @get:Rule
    val flag = ProviderTruthFlagRule(enabled = true)

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        HiddenChatsSession.reset()
        forgetAppLockManager()
        seed()
    }

    @After
    fun tearDown() {
        models.clear()
        ProcessReturn.leave()
        app.baseConfig.isAppPasswordProtectionOn = false
        LockScope.set(app, LockScope.WholeApp)
        HiddenChats.get(app).unhide(SPAM)
        forgetAppLockManager()
        HiddenChatsSession.reset()
        resetSharedMessagesDatabase()
    }

    /** The commons lock manager keeps the first test's Application; it is made again for this one. */
    private fun forgetAppLockManager() {
        AppLockManager::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
    }

    private fun message(id: Long, thread: Long, body: String, sim: Int = 0) = Message(
        id = id, body = body, type = Telephony.Sms.MESSAGE_TYPE_INBOX, status = 0, participants = ArrayList(),
        date = id.toInt(), read = true, threadId = thread, isMMS = false, attachment = null,
        senderPhoneNumber = number(thread), senderName = "", senderPhotoUri = "", subscriptionId = sim,
    )

    private fun number(thread: Long) = "+155500012$thread"

    private fun conversation(thread: Long, title: String) = Conversation(
        threadId = thread, snippet = "", date = 100, read = true, title = title, photoUri = "",
        isGroupConversation = false, phoneNumber = number(thread),
    )

    private fun seed() = runBlocking(Dispatchers.IO) {
        app.conversationsDB.insertOrUpdate(conversation(SPAM, "Pizza deals"))
        app.conversationsDB.insertOrUpdate(conversation(SHOWN, "Pizza friend"))
        app.messagesDB.insertOrUpdate(message(1, SPAM, "pizza at nine https://deals.example/a", sim = 3))
        app.messagesDB.insertOrUpdate(message(2, SHOWN, "pizza at ten https://friend.example/b", sim = 3))
        app.conversationsDB.insertSpamThread(SpamThread(SPAM, number(SPAM), SpamCause.UNKNOWN, spamSince = 1L))
    }

    /** J4-5: holds the models, so their return watches are taken off the process lifecycle after each test. */
    private val models = ViewModelStore()
    private var made = 0

    private val factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MainSearchViewModel(app, SavedStateHandle()) as T
    }

    /** A new search model, cleared after the test. */
    private fun search() = ViewModelProvider(models, factory)["search-${made++}", MainSearchViewModel::class.java]

    private fun lockSpam() {
        app.baseConfig.isAppPasswordProtectionOn = true
        LockScope.set(app, LockScope.ChosenParts)
        app.appLockManager.lock()
    }

    @Test
    fun searchLeavesTheSpamChatOutOfMessagesTitlesAndNumbers() = runBlocking<Unit>(Dispatchers.IO) {
        val search = search()

        assertThat(search.searchDatabase("pizza").map { it.threadId }.toSet()).containsExactly(SHOWN)
        assertThat(search.searchDatabase("00012").map { it.threadId }.toSet()).containsExactly(SHOWN)
    }

    @Test
    fun withTheFlagOffSearchStillFindsEveryChat() = runBlocking<Unit>(Dispatchers.IO) {
        ProviderTruthFlag.enabled = false

        assertThat(search().searchDatabase("pizza").map { it.threadId }.toSet()).containsExactly(SPAM, SHOWN)
    }

    @Test
    fun theSpamChatIsFoundInTheSpamSectionOnlyAndOpensItsChat() = runBlocking<Unit>(Dispatchers.IO) {
        val hits = search().searchSpamChats("pizza")

        assertThat(hits.map { it.open.threadId }).containsExactly(SPAM)
        assertThat(hits.single().row.snippet).contains("nine")
    }

    @Test
    fun theSpamSectionIsEmptyWhileTheLockCoversSpam() = runBlocking<Unit>(Dispatchers.IO) {
        lockSpam()

        assertThat(search().searchSpamChats("pizza")).isEmpty()
    }

    @Test
    fun aSpamChatHiddenBehindTheLockIsNotInTheSpamSection() = runBlocking<Unit>(Dispatchers.IO) {
        app.baseConfig.isAppPasswordProtectionOn = true
        HiddenChats.get(app).hide(SPAM)

        assertThat(search().searchSpamChats("pizza")).isEmpty()
    }

    @Test
    fun aNoteInASpamChatIsNotFound() = runBlocking<Unit>(Dispatchers.IO) {
        app.messageNotes.setNote(message(1, SPAM, "pizza at nine https://deals.example/a"), "pizza coupon")
        app.messageNotes.setNote(message(2, SHOWN, "pizza at ten https://friend.example/b"), "pizza night")

        assertThat(search().searchNotes("pizza").map { it.threadId }).containsExactly(SHOWN)
    }

    @Test
    fun theLinksTileTheStarsAndTheSimTileLeaveOutTheSpamChat() = runBlocking<Unit>(Dispatchers.IO) {
        val star = StarredMessage(1, false, SPAM, number(SPAM), 1, 0, 1, "starred spam")
        app.starredDB.insert(star)
        app.starredDB.insert(star.copy(id = 2, threadId = SHOWN, address = number(SHOWN), date = 2, snapshotBody = "x"))

        val links = SearchShortcuts.load(app, InboxFilter.Links, emptyList())
        val starred = SearchShortcuts.load(app, InboxFilter.Starred, emptyList())
        val onSim = SimFilter.load(app, 3)
        val starredScreen = StarredList.load(app)

        assertThat(links.hits.map { it.threadId }).containsExactly(SHOWN)
        assertThat(starred.hits.map { it.threadId }).containsExactly(SHOWN)
        assertThat(onSim).containsExactly(SHOWN)
        assertThat(starredScreen.items.map { it.threadId }).containsExactly(SHOWN)
    }

    @Test
    fun manySpamMatchesNeverPushTheMessagesMatchOutOfTheCap() = runBlocking<Unit>(Dispatchers.IO) {
        // H-1: more than the 100-row cap of newer Spam matches, and one older Messages match.
        (100L..220L).forEach { app.messagesDB.insertOrUpdate(message(it, SPAM, "pizza offer $it")) }
        val search = search()

        val shown = search.searchDatabase("pizza").filter { it.messageId != CONVERSATION_HIT }
        val spam = search.searchSpamChats("pizza").single()

        assertThat(shown.map { it.threadId }.toSet()).containsExactly(SHOWN)
        assertThat(shown.map { it.messageId }).contains(2L)
        assertThat(spam.open.threadId).isEqualTo(SPAM)
        assertThat(spam.row.count).isEqualTo(com.glooboo.umessage.interfaces.SEARCH_RESULTS_LIMIT)
    }

    @Test
    fun theTileAndRetentionQueriesLeaveSpamOutBeforeTheirCaps() = runBlocking<Unit>(Dispatchers.IO) {
        // H-6, H-7: newer Spam links, and older Spam rows at the head of the retention scan.
        (100L..120L).forEach { app.messagesDB.insertOrUpdate(message(it, SPAM, "see https://deals.example/$it")) }

        val links = app.messagesDB.getLinkCandidatesOutsideSpam(1)
        val oldest = app.messagesDB.getRetentionCandidatesOutsideSpam(before = 1_000, limit = 1, offset = 0)

        assertThat(links.map { it.threadId }).containsExactly(SHOWN)
        assertThat(oldest.map { it.id }).containsExactly(2L)
    }

    @Test
    fun manyMessagesMatchesNeverPushTheSpamChatOutOfTheSpamSection() = runBlocking<Unit>(Dispatchers.IO) {
        // H-1, Spam side: more than the cap of newer Messages matches, one older Spam match.
        (100L..220L).forEach { app.messagesDB.insertOrUpdate(message(it, SHOWN, "pizza tonight $it")) }

        val spam = search().searchSpamChats("pizza").single()

        assertThat(spam.open.threadId).isEqualTo(SPAM)
        assertThat(spam.row.count).isEqualTo(1)
    }

    @Test
    fun aFoldedSearchKeepsItsPlaceAlsoForRowsNotBackfilledYet() = runBlocking<Unit>(Dispatchers.IO) {
        // Persian letter forms in the texts, Arabic ones typed: folded on both sides (MC-15). Rows 301 and 302 have
        // their folded column still empty, as before the backfill, so only the old SQL fold finds them.
        MessageFoldBackfill.reset()
        app.messagesDB.insertOrUpdate(message(300, SPAM, "سلام علی"))
        app.messagesDB.insertOrUpdate(message(301, SPAM, "سلام علی").apply { bodyFolded = "" })
        app.messagesDB.insertOrUpdate(message(302, SHOWN, "سلام علی").apply { bodyFolded = "" })
        app.messagesDB.insertOrUpdate(message(303, SHOWN, "سلام علی"))

        val outside = app.messagesDB.searchMessages("علي", SearchPlace.OUTSIDE_SPAM).map { it.id }
        val inSpam = app.messagesDB.searchMessages("علي", SearchPlace.SPAM).map { it.id }

        assertThat(outside).containsExactly(303L, 302L)
        assertThat(inSpam).containsExactly(301L, 300L)
    }

    @Test
    fun theSpamSectionGoesWhenTheAppComesBackLocked() = runBlocking<Unit>(Dispatchers.IO) {
        // H-16: found while Spam was open; the lock locks again while the app is away.
        val search = search()
        val hits = search.searchSpamChats("pizza")
        search.state.blockedHits = hits.map { it.row }
        search.state.spamTargets = hits.associate { it.row.address to it.open }

        search.onAppReturned()
        assertThat(search.state.blockedHits).isNotEmpty()

        lockSpam()
        search.onAppReturned()
        assertThat(search.state.blockedHits).isEmpty()
        assertThat(search.state.spamTargets).isEmpty()
        assertThat(search.state.shownBlockedHits(typed = true, spamLocked = true)).isEmpty()
    }

    /** The Spam hits for "pizza", found while Spam is open, put on [search]'s page. */
    private fun showSpamHits(search: MainSearchViewModel) {
        val hits = runBlocking(Dispatchers.IO) { search.searchSpamChats("pizza") }
        search.state.query = "pizza"
        search.state.blockedHits = hits.map { it.row }
        search.state.spamTargets = hits.associate { it.row.address to it.open }
    }

    private fun idleMain() = shadowOf(Looper.getMainLooper()).idle()

    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + WAIT_MS
        while (true) {
            idleMain()
            if (condition()) return
            check(System.currentTimeMillis() < deadline) { "condition not met" }
            Thread.sleep(POLL_MS)
        }
    }

    @Test
    fun theSpamSectionComesBackOnceSpamIsUnlocked() {
        // H-20: it went when the app came back locked; once Spam is unlocked it is found again, without retyping.
        val search = search()
        showSpamHits(search)
        lockSpam()
        search.onAppReturned()
        assertThat(search.state.blockedHits).isEmpty()

        app.appLockManager.unlock()
        search.state.spamUnlocked()
        awaitUntil { search.state.blockedHits.isNotEmpty() }

        assertThat(search.state.spamTargets.values.map { it.threadId }).containsExactly(SPAM)
        assertThat(search.state.spamHidden).isFalse()
    }

    @Test
    fun aSearchRunWhileSpamWasLockedFindsSpamOnceUnlocked() {
        // H-20: the query was typed while the lock covered Spam, so its Spam section was left out.
        lockSpam()
        val search = search()
        search.onQueryChange("pizza")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(SEARCH_DEBOUNCE_MS))
        awaitUntil { search.state.spamHidden }
        assertThat(search.state.blockedHits).isEmpty()

        app.appLockManager.unlock()
        search.state.spamUnlocked()
        awaitUntil { search.state.blockedHits.isNotEmpty() }

        assertThat(search.state.spamTargets.values.map { it.threadId }).containsExactly(SPAM)
    }

    @Test
    fun theAppReturnWatchOfTheModelHidesTheSpamSection() {
        // H-21: the model's watch is on the process lifecycle; a real return to the front runs onAppReturned.
        val search = search()
        showSpamHits(search)
        ProcessReturn.leave()
        ProcessReturn.comeBack()
        assertThat(search.state.blockedHits).isNotEmpty()

        ProcessReturn.leave()
        lockSpam()
        // Going to the background alone hides nothing; the return does.
        assertThat(search.state.blockedHits).isNotEmpty()
        ProcessReturn.comeBack()

        assertThat(search.state.blockedHits).isEmpty()
        assertThat(search.state.spamTargets).isEmpty()
    }

    /** The first Spam search of [search] waits for [gate] and finds nothing; the next ones search for real. */
    private fun gatedSpamSearch(search: MainSearchViewModel, gate: CompletableDeferred<Unit>): MutableList<String> {
        val asked = mutableListOf<String>()
        search.runSpamSearch = { text ->
            asked += text
            if (asked.size == 1) {
                gate.await()
                emptyList()
            } else {
                search.searchSpamChats(text)
            }
        }
        return asked
    }

    private fun typeAndWait(search: MainSearchViewModel, text: String) {
        search.onQueryChange(text)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(SEARCH_DEBOUNCE_MS))
    }

    @Test
    fun anUnlockWhileASearchRunsSearchesSpamOnceMoreAsItEnds() {
        // J4-2: the page saw Spam open while the search ran, after that search had read the lock.
        val search = search()
        val gate = CompletableDeferred<Unit>()
        val asked = gatedSpamSearch(search, gate)
        search.state.spamHidden = true
        typeAndWait(search, "pizza")
        awaitUntil { asked.size == 1 }

        search.state.spamUnlocked()
        gate.complete(Unit)
        awaitUntil { search.state.blockedHits.isNotEmpty() }

        assertThat(asked).containsExactly("pizza", "pizza")
        assertThat(search.state.spamTargets.values.map { it.threadId }).containsExactly(SPAM)
    }

    @Test
    fun spamOpenedWhileASearchRanIsSearchedOnceMore() {
        // J4-2: the lock was read as the search started (locked) and is open as it ends.
        lockSpam()
        val search = search()
        val gate = CompletableDeferred<Unit>()
        val asked = gatedSpamSearch(search, gate)
        typeAndWait(search, "pizza")
        awaitUntil { asked.size == 1 }

        app.appLockManager.unlock()
        gate.complete(Unit)
        awaitUntil { search.state.blockedHits.isNotEmpty() }

        assertThat(asked).hasSize(2)
        assertThat(search.state.spamHidden).isFalse()
    }

    @Test
    fun typingDuringTheSpamSearchAgainCancelsItAndTheNewQueryWins() {
        val search = search()
        showSpamHits(search)
        search.state.hideSpamHits()
        val gate = CompletableDeferred<Unit>()
        val asked = gatedSpamSearch(search, gate)
        search.state.spamUnlocked()
        idleMain()
        assertThat(asked).containsExactly("pizza")

        typeAndWait(search, "nine")
        awaitUntil { search.state.blockedHits.isNotEmpty() }
        gate.complete(Unit)
        idleMain()

        assertThat(asked).containsExactly("pizza", "nine").inOrder()
        assertThat(search.state.blockedHits.single().snippet).contains("nine")
    }

    @Test
    fun aShortQueryAndClosingForgetTheLeftOutSpamSection() {
        val search = search()
        search.state.hideSpamHits()
        search.onQueryChange("p")
        assertThat(search.state.spamHidden).isFalse()

        search.state.hideSpamHits()
        search.state.close()
        assertThat(search.state.spamHidden).isFalse()
    }

    @Test
    fun withTheFlagOffAnUnlockSearchesNothing() {
        ProviderTruthFlag.enabled = false
        val search = search()
        var calls = 0
        search.runSpamSearch = {
            calls++
            emptyList()
        }
        search.state.query = "pizza"
        search.state.hideSpamHits()

        search.state.spamUnlocked()
        idleMain()

        assertThat(calls).isEqualTo(0)
    }

    @Test
    fun aFoldedNotesSearchKeepsItsPlace() = runBlocking<Unit>(Dispatchers.IO) {
        // H-21: Arabic letter forms typed, Persian ones in the notes (MC-15); the place is kept in the folded SQL.
        val notes = app.messageNotes
        notes.setNote(message(1, SPAM, "pizza at nine https://deals.example/a"), "سلام علی")
        notes.setNote(message(2, SHOWN, "pizza at ten https://friend.example/b"), "سلام علی")
        notes.relink()

        assertThat(notes.search("علي", SearchPlace.OUTSIDE_SPAM).map { it.threadId }).containsExactly(SHOWN)
        assertThat(notes.search("علي", SearchPlace.SPAM).map { it.threadId }).containsExactly(SPAM)
        assertThat(notes.search("علي", SearchPlace.ALL).map { it.threadId }).containsExactly(SPAM, SHOWN)
    }

    @Test
    fun aSpamRowOfAnotherSenderHidesNothing() = runBlocking<Unit>(Dispatchers.IO) {
        // S9: after a rebuilt store the row no longer names this chat's sender, so it counts for nothing.
        app.conversationsDB.updateSpamThreadSender(SPAM, "+15550009999", SpamCause.UNKNOWN)

        assertThat(SpamReaders.threadIds(app)).isEmpty()
        assertThat(search().searchDatabase("pizza").map { it.threadId }.toSet()).containsExactly(SPAM, SHOWN)
    }

    @Test
    fun withTheFlagOffThereAreNoSpamChatsToLeaveOut() {
        ProviderTruthFlag.enabled = false

        assertThat(SpamReaders.threadIds(app)).isEmpty()
    }

    private companion object {
        const val SPAM = 51L
        const val SHOWN = 52L
        const val WAIT_MS = 5_000L
        const val POLL_MS = 10L
    }
}

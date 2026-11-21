package com.glooboo.umessage.ui.main

import android.app.Application
import android.provider.Telephony
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.extensions.messagesDB
import com.glooboo.umessage.helpers.HiddenChats
import com.glooboo.umessage.helpers.HiddenChatsSession
import com.glooboo.umessage.helpers.ProviderTruthFlag
import com.glooboo.umessage.helpers.SpamReaders
import com.glooboo.umessage.helpers.StarredList
import com.glooboo.umessage.helpers.messageNotes
import com.glooboo.umessage.helpers.starredDB
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.models.StarredMessage
import com.glooboo.umessage.spam.SpamThread
import com.glooboo.umessage.spam.engine.SpamCause
import com.glooboo.umessage.testing.ProviderTruthFlagRule
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.glooboo.umessage.ui.lock.LockScope
import com.google.common.truth.Truth.assertThat
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

    private fun search() = MainSearchViewModel(app, SavedStateHandle())

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
    }
}

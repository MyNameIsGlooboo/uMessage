package com.glooboo.umessage.ui.conversations

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.R
import com.glooboo.umessage.helpers.ProviderTruthFlag
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.spam.AllowGroup
import com.glooboo.umessage.spam.BlockReceipt
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamRepository
import com.glooboo.umessage.spam.SpamRule
import com.glooboo.umessage.testing.ChatNotices
import com.glooboo.umessage.testing.ProviderTruthFlagRule
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.glooboo.umessage.ui.lock.SpamChatGate
import com.glooboo.umessage.ui.spam.FakeSpamRepository
import com.glooboo.umessage.ui.spam.RuleNotes
import com.glooboo.umessage.ui.spam.SenderBlocking
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Provider truth, step C (4.1: U5, M-SP3): Block and "Report spam" from Messages hide the rows at once, block the
 * sender (its automatic allow rule and group membership go), sort the chats again, and Undo puts all of it back. A row
 * is never left hidden: the end of the Undo window shows it again. Numbers are made up.
 */
@RunWith(AndroidJUnit4::class)
class MessagesSpamMovesTest {

    @get:Rule
    val flag = ProviderTruthFlagRule(enabled = true)

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val repository = FakeSpamRepository(initialBlocked = emptyList(), initialRules = emptyList())
    private val hidden = HashSet<Long>()
    private val events = mutableListOf<ConversationsEvent>()
    private val sorted = mutableListOf<Collection<Long>>()

    private fun moves(scope: CoroutineScope, repo: SpamRepository = repository) = MessagesSpamMoves(
        context, scope, hidden, rebuild = {}, events = { events += it }, repository = { repo },
        requestSort = { sorted.add(it.toList()) },
    )

    private fun chat(id: Long, number: String, read: Boolean = true) = Conversation(
        threadId = id, snippet = "hi", date = 1, read = read, title = number, photoUri = "",
        isGroupConversation = false, phoneNumber = number,
    )

    private fun rules() = runBlocking { repository.rules.first() }

    @Before
    fun setUp() = SpamChatGate.Known.clear()

    @After
    fun tearDown() {
        SpamChatGate.Known.clear()
        resetSharedMessagesDatabase()
    }

    private suspend fun seedAutomaticAllow(group: AllowGroup): Long {
        val id = repository.addRule(
            SpamRule(
                kind = ListKind.ALLOW, type = RuleType.NUMBER, pattern = SENDER, note = RuleNotes.ALLOWED_FROM_KEYWORD,
            ),
        )
        repository.joinGroup(group, SENDER)
        return id
    }

    /** Waits inside the caller's scope until the move with [token] was recorded (its block was written). */
    private suspend fun awaitMove(token: Int) {
        repeat(MAX_YIELDS) {
            if (events.size >= token) return
            yield()
            Thread.sleep(1)
        }
        error("the move did not finish")
    }

    @Test
    fun blockHidesTheRowAtOnceAndMovesTheChat(): Unit = runBlocking {
        seedAutomaticAllow(AllowGroup.CODE)
        coroutineScope {
            assertThat(moves(this).block(listOf(chat(7, SENDER, read = false)), token = 1L, report = false)).isTrue()
            // Hidden at once, before the block is written.
            assertThat(hidden).containsExactly(7L)
        }
        assertThat(rules().map { it.kind to it.note }).containsExactly(ListKind.BLOCK to SenderBlocking.NOTE)
        assertThat(repository.groupMembers.first()).isEmpty()
        assertThat(sorted).containsExactly(listOf(7L))
        assertThat(events).containsExactly(ConversationsEvent.Blocked(1L, 1))
        assertThat(hidden).containsExactly(7L)
    }

    @Test
    fun undoRestoresTheRulesTheMembershipsAndShowsTheRow(): Unit = runBlocking {
        val wordAllow = seedAutomaticAllow(AllowGroup.WROTE)
        coroutineScope {
            val helper = moves(this)
            helper.block(listOf(chat(7, SENDER, read = false)), token = 1L, report = false)
            awaitMove(1)
            assertThat(helper.undo(1L)).isTrue()
        }
        assertThat(rules().map { it.id }).containsExactly(wordAllow)
        assertThat(repository.groupMembers.first().map { it.group }).containsExactly(AllowGroup.WROTE)
        assertThat(hidden).isEmpty()
        assertThat(sorted).containsExactly(listOf(7L), listOf(7L))
    }

    @Test
    fun theEndOfTheUndoWindowShowsTheRowAgain(): Unit = runBlocking {
        coroutineScope {
            val helper = moves(this)
            helper.block(listOf(chat(8, OTHER)), token = 2L, report = false)
            awaitMove(1)
            assertThat(hidden).contains(8L)
            assertThat(helper.commit(2L)).isTrue()
            // A token it does not know changes nothing.
            assertThat(helper.commit(99L)).isFalse()
        }
        assertThat(hidden).isEmpty()
    }

    @Test
    fun reportSpamEqualsBlockAndNoLongerArchives(): Unit = runBlocking {
        coroutineScope { moves(this).block(listOf(chat(7, SENDER)), token = 1L, report = true) }
        assertThat(rules().single().note).isEqualTo(RuleNotes.REPORTED_SPAM)
        assertThat(events).containsExactly(ConversationsEvent.ReportedSpam(1L, 1, archived = false))
    }

    @Test
    fun aFailedBlockShowsTheRowAgainAndSaysSo(): Unit = runBlocking {
        val failing = object : SpamRepository by repository {
            override suspend fun blockAddress(address: String, note: String): BlockReceipt? = error("disk full")
        }
        coroutineScope { moves(this, failing).block(listOf(chat(7, SENDER)), token = 1L, report = false) }
        assertThat(hidden).isEmpty()
        assertThat(events).containsExactly(ConversationsEvent.Failed)
        assertThat(sorted).isEmpty()
    }

    @Test
    fun withTheFlagOffNothingChanges(): Unit = runBlocking {
        ProviderTruthFlag.enabled = false
        coroutineScope {
            assertThat(moves(this).block(listOf(chat(7, SENDER)), token = 1L, report = false)).isFalse()
            assertThat(moves(this).observeCount(0L)).isFalse()
        }
        assertThat(hidden).isEmpty()
        assertThat(rules()).isEmpty()
    }

    @Test
    fun aUserAllowRuleThatStillWinsTakesTheBlockBackAndSaysWhy(): Unit = runBlocking {
        val prefixAllow = object : SpamRepository by repository {
            override suspend fun blockAddress(address: String, note: String): BlockReceipt? =
                repository.blockAddress(address, note)?.copy(stillAllowedBy = 77L)
        }
        coroutineScope { moves(this, prefixAllow).block(listOf(chat(7, SENDER)), token = 1L, report = false) }

        // C-3: no "Blocked" for a chat that stays in Messages; the row shows again; nothing is left changed.
        assertThat(events).containsExactly(ConversationsEvent.Message(R.string.pt_block_still_allowed))
        assertThat(hidden).isEmpty()
        assertThat(rules()).isEmpty()
        assertThat(sorted).isEmpty()
    }

    @Test
    fun aSenderThatCannotBeReadIsNotBlockedAndSaysItFailed(): Unit = runBlocking {
        val unreadable = object : SpamRepository by repository {
            override suspend fun blockAddress(address: String, note: String): BlockReceipt? = null
        }
        coroutineScope { moves(this, unreadable).block(listOf(chat(7, SENDER)), token = 1L, report = false) }
        assertThat(events).containsExactly(ConversationsEvent.Failed)
        assertThat(hidden).isEmpty()
    }

    @Test
    fun aMixedChoiceCountsOnlyTheChatsThatMoved(): Unit = runBlocking {
        val oneStays = object : SpamRepository by repository {
            override suspend fun blockAddress(address: String, note: String): BlockReceipt? =
                repository.blockAddress(address, note)?.let {
                    if (address == OTHER) it.copy(stillAllowedBy = 77L) else it
                }
        }
        coroutineScope {
            moves(this, oneStays).block(listOf(chat(7, SENDER), chat(8, OTHER)), token = 1L, report = false)
        }
        // D-5: the snackbar counts the moved chat and says why the other stayed.
        assertThat(events).containsExactly(ConversationsEvent.Blocked(1L, 1, stayed = 1))
        assertThat(hidden).containsExactly(7L)
        assertThat(rules().map { it.pattern }).containsExactly(SENDER)
    }

    @Test
    fun aCancelledSnackbarClosesTheWindowSoNoRowStaysHidden(): Unit = runBlocking {
        lateinit var helper: MessagesSpamMoves
        coroutineScope {
            helper = moves(this)
            helper.block(listOf(chat(7, SENDER)), token = 1L, report = false)
            awaitMove(1)
            assertThat(hidden).containsExactly(7L)
            // The snackbar of the event, cancelled unanswered (the tab left): C-2.
            val shown = launch {
                commitIfCancelled({ helper.commit(1L) }) { kotlinx.coroutines.awaitCancellation() }
            }
            yield()
            shown.cancel()
        }
        assertThat(hidden).isEmpty()
        // The block itself stays: the chat has moved to Spam (by the sort, not here).
        assertThat(rules().map { it.kind }).containsExactly(ListKind.BLOCK)
    }

    @Test
    fun aBlockCancelsTheQuietSpamNoticeAndTellsTheLockGateTheChatIsInSpam(): Unit = runBlocking {
        ChatNotices.post(context, 7L)
        coroutineScope { moves(this).block(listOf(chat(7, SENDER)), token = 1L, report = false) }
        assertThat(ChatNotices.isShown(context, 7L)).isFalse()
        // D-8, E-3: the gate covers the chat before its next read.
        assertThat(SpamChatGate.Known.inSpam(7L)).isTrue()
    }

    @Test
    fun aBlockThatStaysKeepsTheNoticeAndNotesNothing(): Unit = runBlocking {
        val prefixAllow = object : SpamRepository by repository {
            override suspend fun blockAddress(address: String, note: String): BlockReceipt? =
                repository.blockAddress(address, note)?.copy(stillAllowedBy = 77L)
        }
        ChatNotices.post(context, 7L)
        coroutineScope { moves(this, prefixAllow).block(listOf(chat(7, SENDER)), token = 1L, report = false) }
        assertThat(ChatNotices.isShown(context, 7L)).isTrue()
        assertThat(SpamChatGate.Known.inSpam(7L)).isNull()
    }

    @Test
    fun anUnreadableSenderIsNotCountedAsKeptByAnAllowRule(): Unit = runBlocking {
        val otherUnreadable = object : SpamRepository by repository {
            override suspend fun blockAddress(address: String, note: String): BlockReceipt? =
                if (address == OTHER) null else repository.blockAddress(address, note)
        }
        coroutineScope {
            moves(this, otherUnreadable).block(listOf(chat(7, SENDER), chat(8, OTHER)), token = 1L, report = false)
        }
        // E-1: "still allowed" is not the reason the other chat stayed.
        assertThat(events).containsExactly(ConversationsEvent.Blocked(1L, 1, stayed = 0))
        assertThat(hidden).containsExactly(7L)
    }

    @Test
    fun aFailingUndoOfAStayingChatIsSilentShowsTheRowAndSaysItIsStillAllowed(): Unit = runBlocking {
        val throwing = object : SpamRepository by repository {
            override suspend fun blockAddress(address: String, note: String): BlockReceipt? =
                repository.blockAddress(address, note)?.copy(stillAllowedBy = 77L)

            override suspend fun undoBlock(receipt: BlockReceipt): Unit = error("disk full")
        }
        coroutineScope { moves(this, throwing).block(listOf(chat(7, SENDER)), token = 1L, report = false) }
        // E-7, F-2: no crash; the row shows again and the snackbar says why it stayed.
        assertThat(hidden).isEmpty()
        assertThat(events).containsExactly(ConversationsEvent.Message(R.string.pt_block_still_allowed))
        assertThat(sorted).isEmpty()
    }

    @Test
    fun closeAllClosesEveryOpenBlockAndReturnsTheirTokens(): Unit = runBlocking {
        lateinit var helper: MessagesSpamMoves
        coroutineScope {
            helper = moves(this)
            helper.block(listOf(chat(7, SENDER)), token = 1L, report = false)
            awaitMove(1)
            helper.block(listOf(chat(8, OTHER)), token = 2L, report = true)
            awaitMove(2)
        }
        assertThat(hidden).containsExactly(7L, 8L)
        // D-4, E-3: the list's screen started again; replayPendingUndo drops the events of these tokens.
        assertThat(helper.closeAll()).containsExactly(1L, 2L)
        assertThat(hidden).isEmpty()
        // Closed: a late Undo or a second close does nothing.
        assertThat(helper.undo(1L)).isFalse()
        assertThat(helper.closeAll()).isEmpty()
        assertThat(rules().map { it.kind }).containsExactly(ListKind.BLOCK, ListKind.BLOCK)
    }

    private companion object {
        const val SENDER = "+15550001234"
        const val OTHER = "+15550001235"
        const val MAX_YIELDS = 5_000
    }
}

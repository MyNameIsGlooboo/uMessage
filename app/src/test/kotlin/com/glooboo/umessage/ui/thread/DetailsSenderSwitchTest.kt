package com.glooboo.umessage.ui.thread

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.spam.AllowGroup
import com.glooboo.umessage.spam.BlockReceipt
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamRepository
import com.glooboo.umessage.spam.SpamRule
import com.glooboo.umessage.testing.ChatNotices
import com.glooboo.umessage.ui.conversations.UnreadTexts
import com.glooboo.umessage.ui.lock.SpamChatGate
import com.glooboo.umessage.ui.spam.FakeSpamRepository
import com.glooboo.umessage.ui.spam.RuleNotes
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Provider truth, step C (C-6): the details screen's Block switch blocks as 4.1 says (read, notification gone,
 * sorted) and switching it off undoes it fully: rules, memberships and unread texts. Numbers are made up.
 */
@RunWith(AndroidJUnit4::class)
class DetailsSenderSwitchTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val repository = FakeSpamRepository(initialBlocked = emptyList(), initialRules = emptyList())
    private val calls = mutableListOf<String>()
    private var restored: UnreadTexts? = null
    private val unread = UnreadTexts(sms = listOf(5L, 6L))

    private val memory = DetailsBlockMemory()

    private fun switch(repo: SpamRepository = repository) = DetailsSenderSwitch(
        context,
        repository = { repo },
        memory = memory,
        requestSort = { calls += "sort$it" },
        markRead = { calls += "read$it" },
        cancelNotification = { calls += "cancel$it" },
        unreadOf = { unread },
        restoreUnread = { _, texts -> restored = texts },
    )

    private fun rules() = runBlocking { repository.rules.first() }

    @Before
    fun setUp() = SpamChatGate.Known.clear()

    @After
    fun tearDown() = SpamChatGate.Known.clear()

    @Test
    fun onAndOffAgainUndoesTheBlockFully(): Unit = runBlocking {
        val wordAllow = repository.addRule(
            SpamRule(
                kind = ListKind.ALLOW, type = RuleType.NUMBER, pattern = SENDER, note = RuleNotes.ALLOWED_FROM_KEYWORD,
            ),
        )
        repository.joinGroup(AllowGroup.WROTE, SENDER)
        val details = switch()

        assertThat(details.block(SENDER, THREAD)).isEqualTo(DetailsSenderSwitch.Outcome.BLOCKED)
        assertThat(calls).containsExactly("cancel$THREAD", "read$THREAD", "sort$THREAD").inOrder()
        assertThat(rules().map { it.kind }).containsExactly(ListKind.BLOCK)

        // D-5: the screen was recreated (a rotation): a new switch with the kept memory still undoes fully.
        switch().unblock(SENDER, THREAD)
        assertThat(rules().map { it.id }).containsExactly(wordAllow)
        assertThat(repository.groupMembers.first().map { it.group }).containsExactly(AllowGroup.WROTE)
        assertThat(restored).isEqualTo(unread)
    }

    @Test
    fun aBlockThatAUserAllowRuleOutranksIsTakenBack(): Unit = runBlocking {
        val prefixAllow = object : SpamRepository by repository {
            override suspend fun blockAddress(address: String, note: String): BlockReceipt? =
                repository.blockAddress(address, note)?.copy(stillAllowedBy = 9L)
        }
        assertThat(switch(prefixAllow).block(SENDER, THREAD)).isEqualTo(DetailsSenderSwitch.Outcome.STILL_ALLOWED)
        assertThat(rules()).isEmpty()
        assertThat(calls).isEmpty()
    }

    @Test
    fun aSenderThatCannotBeReadSaysNothingWasBlocked(): Unit = runBlocking {
        val unreadable = object : SpamRepository by repository {
            override suspend fun blockAddress(address: String, note: String): BlockReceipt? = null
        }
        assertThat(switch(unreadable).block(SENDER, THREAD)).isEqualTo(DetailsSenderSwitch.Outcome.NOTHING_BLOCKED)
        assertThat(calls).isEmpty()
    }

    @Test
    fun offWithoutItsOwnBlockRemovesTheSendersBlockRules(): Unit = runBlocking {
        repository.addRule(SpamRule(kind = ListKind.BLOCK, type = RuleType.NUMBER, pattern = SENDER))
        switch().unblock(SENDER, THREAD)
        assertThat(rules()).isEmpty()
        assertThat(restored).isNull()
    }

    @Test
    fun aBlockCancelsTheQuietSpamNoticeAndTellsTheLockGate(): Unit = runBlocking {
        ChatNotices.post(context, THREAD)
        // The real notification cancel, the rest faked.
        val details = DetailsSenderSwitch(
            context, repository = { repository }, memory = memory, requestSort = {}, markRead = {},
            unreadOf = { unread }, restoreUnread = { _, _ -> },
        )
        assertThat(details.block(SENDER, THREAD)).isEqualTo(DetailsSenderSwitch.Outcome.BLOCKED)
        assertThat(ChatNotices.isShown(context, THREAD)).isFalse()
        // D-8, E-3: the gate covers the chat before its next read.
        assertThat(SpamChatGate.Known.inSpam(THREAD)).isTrue()
    }

    @Test
    fun aBlockThatIsTakenBackNotesNothing(): Unit = runBlocking {
        val prefixAllow = object : SpamRepository by repository {
            override suspend fun blockAddress(address: String, note: String): BlockReceipt? =
                repository.blockAddress(address, note)?.copy(stillAllowedBy = 9L)
        }
        switch(prefixAllow).block(SENDER, THREAD)
        assertThat(SpamChatGate.Known.inSpam(THREAD)).isNull()
    }

    private companion object {
        const val SENDER = "+15550001234"
        const val THREAD = 42L
    }
}

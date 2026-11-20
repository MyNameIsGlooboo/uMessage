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
import com.glooboo.umessage.ui.conversations.UnreadTexts
import com.glooboo.umessage.ui.spam.FakeSpamRepository
import com.glooboo.umessage.ui.spam.RuleNotes
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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

    private fun switch(repo: SpamRepository = repository) = DetailsSenderSwitch(
        context,
        repository = { repo },
        requestSort = { calls += "sort$it" },
        markRead = { calls += "read$it" },
        cancelNotification = { calls += "cancel$it" },
        unreadOf = { unread },
        restoreUnread = { _, texts -> restored = texts },
    )

    private fun rules() = runBlocking { repository.rules.first() }

    @Test
    fun onAndOffAgainUndoesTheBlockFully(): Unit = runBlocking {
        val wordAllow = repository.addRule(
            SpamRule(
                kind = ListKind.ALLOW, type = RuleType.NUMBER, pattern = SENDER, note = RuleNotes.ALLOWED_FROM_KEYWORD,
            ),
        )
        repository.joinGroup(AllowGroup.WROTE, SENDER)
        val details = switch()

        assertThat(details.block(SENDER, THREAD)).isTrue()
        assertThat(calls).containsExactly("cancel$THREAD", "read$THREAD", "sort$THREAD").inOrder()
        assertThat(rules().map { it.kind }).containsExactly(ListKind.BLOCK)

        details.unblock(SENDER, THREAD)
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
        assertThat(switch(prefixAllow).block(SENDER, THREAD)).isFalse()
        assertThat(rules()).isEmpty()
        assertThat(calls).isEmpty()
    }

    @Test
    fun offWithoutItsOwnBlockRemovesTheSendersBlockRules(): Unit = runBlocking {
        repository.addRule(SpamRule(kind = ListKind.BLOCK, type = RuleType.NUMBER, pattern = SENDER))
        switch().unblock(SENDER, THREAD)
        assertThat(rules()).isEmpty()
        assertThat(restored).isNull()
    }

    private companion object {
        const val SENDER = "+15550001234"
        const val THREAD = 42L
    }
}

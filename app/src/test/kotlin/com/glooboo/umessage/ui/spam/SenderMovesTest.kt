package com.glooboo.umessage.ui.spam

import com.glooboo.umessage.spam.AllowGroup
import com.glooboo.umessage.spam.BlockReceipt
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamRepository
import com.glooboo.umessage.spam.SpamRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Provider truth, step C (4.1: U5, M-SP3): Block from Messages removes the sender's automatic allow rules and group
 * memberships and Undo puts all of them back; Undo of "Not spam" removes only the rules it created itself.
 */
class SenderMovesTest {

    private val repository = FakeSpamRepository(initialBlocked = emptyList(), initialRules = emptyList())

    private fun rules() = runBlocking { repository.rules.first() }
    private fun members() = runBlocking { repository.groupMembers.first() }

    @Test
    fun blockRemovesTheAutomaticAllowAndTheMembershipAndUndoPutsThemBack(): Unit = runBlocking {
        val wordAllow = repository.addRule(
            SpamRule(
                kind = ListKind.ALLOW, type = RuleType.NUMBER, pattern = SENDER, note = RuleNotes.ALLOWED_FROM_KEYWORD,
            ),
        )
        repository.joinGroup(AllowGroup.CODE, SENDER)

        val receipts = SenderMoves.blockSenders(repository, listOf(SENDER, " ", SENDER))

        assertThat(receipts).hasSize(1)
        assertThat(rules().map { it.kind to it.pattern }).containsExactly(ListKind.BLOCK to SENDER)
        assertThat(rules().single().note).isEqualTo(SenderBlocking.NOTE)
        assertThat(members()).isEmpty()

        SenderMoves.undoBlocks(repository, receipts)

        assertThat(rules().map { it.id }).containsExactly(wordAllow)
        assertThat(members().map { it.group }).containsExactly(AllowGroup.CODE)
    }

    @Test
    fun reportSpamUsesItsOwnNote(): Unit = runBlocking {
        SenderMoves.blockSenders(repository, listOf(SENDER), RuleNotes.REPORTED_SPAM)
        assertThat(rules().single().note).isEqualTo(RuleNotes.REPORTED_SPAM)
    }

    @Test
    fun aFailedBlockTakesBackTheBlocksAlreadyMade() {
        val failing = object : SpamRepository by repository {
            override suspend fun blockAddress(address: String, note: String): BlockReceipt? {
                check(address != OTHER) { "disk full" }
                return repository.blockAddress(address, note)
            }
        }
        assertThrows(IllegalStateException::class.java) {
            runBlocking { SenderMoves.blockSenders(failing, listOf(SENDER, OTHER)) }
        }
        assertThat(rules()).isEmpty()
    }

    @Test
    fun notSpamAddsAUserAllowRuleAndUndoRemovesIt(): Unit = runBlocking {
        val change = SenderMoves.notSpam(repository, listOf(SENDER))

        val allow = rules().single()
        assertThat(allow.kind).isEqualTo(ListKind.ALLOW)
        assertThat(RuleNotes.isMadeByFilter(allow.note)).isFalse()
        assertThat(change.createdRuleIds).containsExactly(allow.id)

        SenderMoves.undoNotSpam(repository, change)
        assertThat(rules()).isEmpty()
    }

    @Test
    fun undoOfNotSpamNeverRemovesARuleThatWasThereBefore(): Unit = runBlocking {
        // A repository that reuses an existing rule for the same value, as the real one does.
        val deduplicating = object : SpamRepository by repository {
            override suspend fun addRule(rule: SpamRule): Long =
                rules().firstOrNull { it.kind == rule.kind && it.pattern == rule.pattern.trim() }?.id
                    ?: repository.addRule(rule)
        }
        val older = deduplicating.addRule(
            SpamRule(kind = ListKind.ALLOW, type = RuleType.NUMBER, pattern = SENDER, note = RuleNotes.ALLOWED_BY_YOU),
        )

        val change = SenderMoves.notSpam(deduplicating, listOf(SENDER, OTHER))
        assertThat(change.createdRuleIds).hasSize(1)

        SenderMoves.undoNotSpam(deduplicating, change)
        assertThat(rules().map { it.id }).containsExactly(older)
    }

    private companion object {
        const val SENDER = "+15550001234"
        const val OTHER = "+15550001235"
    }
}

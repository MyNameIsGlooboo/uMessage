package com.glooboo.umessage.ui.thread

import androidx.activity.ComponentActivity
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarHostState
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.R
import com.glooboo.umessage.spam.BlockReceipt
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamRepository
import com.glooboo.umessage.spam.SpamRule
import com.glooboo.umessage.spam.engine.SenderVerdict
import com.glooboo.umessage.spam.engine.SpamCause
import com.glooboo.umessage.testing.ChatNotices
import com.glooboo.umessage.ui.lock.SpamChatGate
import com.glooboo.umessage.ui.spam.FakeSpamRepository
import com.glooboo.umessage.ui.components.isolateNumbers
import com.glooboo.umessage.ui.spam.RulesActivity
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLooper

/**
 * Provider truth, step C (C-3, C-4, D-9): the chat screen's Block and Unblock say what really happened. A block that
 * a user allow rule outranks, or that blocked nothing, says so and keeps nothing; Unblock tells its five outcomes
 * apart and offers Undo only when something was removed. Numbers are made up.
 */
@RunWith(AndroidJUnit4::class)
class ThreadSpamMovesTest {

    private val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
    private val scope = CoroutineScope(Job() + Dispatchers.Main)
    private val snackbar = SnackbarHostState()
    private val repository = FakeSpamRepository(initialBlocked = emptyList(), initialRules = emptyList())
    private var verdict: SenderVerdict? = null

    private fun moves(repo: SpamRepository = repository, thread: Long = 0L) =
        ThreadSpamMoves(activity, scope, snackbar, { repo }, threadId = { thread }, place = { verdict })

    @After
    fun tearDown() {
        scope.cancel()
        SpamChatGate.Known.clear()
    }

    private fun rules() = runBlocking { repository.rules.first() }

    private fun shown(): SnackbarData {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (true) {
            ShadowLooper.idleMainLooper()
            snackbar.currentSnackbarData?.let { return it }
            check(System.currentTimeMillis() < deadline) { "no snackbar" }
            Thread.sleep(10)
        }
    }

    private fun text(res: Int) = activity.getString(res)

    private fun block(sender: String, kind: ListKind = ListKind.BLOCK, type: RuleType = RuleType.NUMBER) =
        runBlocking { repository.addRule(SpamRule(kind = kind, type = type, pattern = sender)) }

    @Test
    fun aBlockThatAUserAllowRuleOutranksSaysSoAndKeepsNothing() {
        val prefixAllow = object : SpamRepository by repository {
            override suspend fun blockAddress(address: String, note: String): BlockReceipt? =
                repository.blockAddress(address, note)?.copy(stillAllowedBy = 9L)
        }
        moves(prefixAllow).block(listOf(SENDER))
        val bar = shown()
        assertThat(bar.visuals.message).isEqualTo(text(R.string.pt_block_still_allowed))
        assertThat(bar.visuals.actionLabel).isNull()
        assertThat(rules()).isEmpty()
    }

    @Test
    fun aBlockThatBlockedNothingSaysItFailed() {
        val unreadable = object : SpamRepository by repository {
            override suspend fun blockAddress(address: String, note: String): BlockReceipt? = null
        }
        moves(unreadable).block(listOf(SENDER))
        assertThat(shown().visuals.message).isEqualTo(text(R.string.thread_block_failed))
    }

    @Test
    fun aBlockOffersUndoThatPutsEverythingBack() {
        moves().block(listOf(SENDER))
        val bar = shown()
        assertThat(bar.visuals.message).isEqualTo(text(R.string.thread_block_done))
        assertThat(rules().map { it.kind }).containsExactly(ListKind.BLOCK)
        bar.performAction()
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (rules().isNotEmpty() && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(10)
        }
        assertThat(rules()).isEmpty()
    }

    @Test
    fun unblockBackInMessagesOffersUndo() {
        block(SENDER)
        verdict = SenderVerdict.Messages(DecisionReason.CONTACT)
        moves().unblock(SENDER)
        val bar = shown()
        assertThat(bar.visuals.message).isEqualTo(text(R.string.thread_unblock_done))
        assertThat(bar.visuals.actionLabel).isEqualTo(text(R.string.thread_report_spam_undo))
    }

    @Test
    fun unblockOfAStillUnknownSenderOffersNotSpam() {
        block(SENDER)
        verdict = SenderVerdict.Spam(SpamCause.UNKNOWN, DecisionReason.UNKNOWN_SENDER)
        moves().unblock(SENDER)
        val bar = shown()
        assertThat(bar.visuals.message).isEqualTo(text(R.string.thread_unblock_done_filtered))
        assertThat(bar.visuals.actionLabel).isEqualTo(text(R.string.ux8c_not_spam))
    }

    @Test
    fun unblockOnAndroidsListOnlyHasNoUndo() {
        verdict = SenderVerdict.Spam(SpamCause.BLOCKED, DecisionReason.BLOCK_RULE)
        moves().unblock(SENDER)
        val bar = shown()
        assertThat(bar.visuals.message).isEqualTo(text(R.string.thread_unblock_done_system))
        assertThat(bar.visuals.actionLabel).isNull()
    }

    @Test
    fun unblockOfAPrefixBlockedSenderNamesTheRuleAndChangesNothing() {
        block(SENDER)
        val prefix = block("+1555", type = RuleType.PREFIX)
        verdict = SenderVerdict.Spam(SpamCause.BLOCKED, DecisionReason.BLOCK_RULE, prefix)
        moves().unblock(SENDER)
        val bar = shown()
        assertThat(bar.visuals.message)
            .isEqualTo(activity.getString(R.string.pt_unblock_refused_prefix, isolateNumbers("+1555")))
        assertThat(bar.visuals.actionLabel).isEqualTo(text(R.string.e_view_rule))
        assertThat(rules().map { it.pattern }).containsExactly("+1555", SENDER)
    }

    @Test
    fun viewRuleOnAPrefixRefusedUnblockOpensTheRulesScreen() {
        block(SENDER)
        val prefix = block("+1555", type = RuleType.PREFIX)
        verdict = SenderVerdict.Spam(SpamCause.BLOCKED, DecisionReason.BLOCK_RULE, prefix)
        moves().unblock(SENDER)
        shown().performAction()
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        var started = shadowOf(activity).nextStartedActivity
        while (started == null && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(10)
            started = shadowOf(activity).nextStartedActivity
        }
        assertThat(started?.component?.className).isEqualTo(RulesActivity::class.java.name)
        // M-1: on the block list, where the refusing prefix rule is.
        assertThat(started?.getStringExtra(RulesActivity.EXTRA_TAB)).isEqualTo(ListKind.BLOCK.name)
    }

    @Test
    fun unblockWhoseVerdictCannotBeReadIsNeutralWithUndo() {
        block(SENDER)
        verdict = null
        moves().unblock(SENDER)
        val bar = shown()
        assertThat(bar.visuals.message)
            .isEqualTo(activity.resources.getQuantityString(R.plurals.blocknum_unblocked, 1, 1))
        assertThat(bar.visuals.actionLabel).isEqualTo(text(R.string.thread_report_spam_undo))
    }

    @Test
    fun unblockThatRemovedNothingAndCannotBeReadSaysItFailed() {
        verdict = null
        moves().unblock(SENDER)
        assertThat(shown().visuals.message).isEqualTo(text(R.string.thread_unblock_failed))
    }

    @Test
    fun aBlockCancelsTheQuietSpamNoticeAndTellsTheLockGate() {
        SpamChatGate.Known.clear()
        ChatNotices.post(activity, THREAD)
        moves(thread = THREAD).block(listOf(SENDER))
        assertThat(shown().visuals.message).isEqualTo(text(R.string.thread_block_done))
        assertThat(ChatNotices.isShown(activity, THREAD)).isFalse()
        // D-8, E-3: the gate covers the chat before its next read.
        assertThat(SpamChatGate.Known.inSpam(THREAD)).isTrue()
    }

    @Test
    fun unblockCancelsTheQuietSpamNotice() {
        block(SENDER)
        verdict = SenderVerdict.Messages(DecisionReason.CONTACT)
        ChatNotices.post(activity, THREAD)
        moves(thread = THREAD).unblock(SENDER)
        shown()
        assertThat(ChatNotices.isShown(activity, THREAD)).isFalse()
    }

    @Test
    fun notSpamCancelsTheQuietSpamNotice() {
        ChatNotices.post(activity, THREAD)
        moves(thread = THREAD).notSpam(SENDER)
        assertThat(shown().visuals.message).isEqualTo(text(R.string.thread_always_allow_done))
        assertThat(ChatNotices.isShown(activity, THREAD)).isFalse()
    }

    private companion object {
        const val SENDER = "+15550001234"
        const val THREAD = 7L
        const val TIMEOUT_MS = 5_000L
    }
}

package com.glooboo.umessage.ui.thread

import android.app.Activity
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import com.glooboo.umessage.R
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.spam.ChatSorter
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamRepository
import com.glooboo.umessage.spam.SpamRule
import com.glooboo.umessage.spam.SpamSorting
import com.glooboo.umessage.spam.data.BlockedNotifier
import com.glooboo.umessage.spam.engine.SenderVerdict
import com.glooboo.umessage.ui.components.ruleValueText
import com.glooboo.umessage.ui.components.showConfirmDialog
import com.glooboo.umessage.ui.lock.SpamChatGate
import com.glooboo.umessage.ui.spam.RuleNotes
import com.glooboo.umessage.ui.spam.RulesActivity
import com.glooboo.umessage.ui.spam.SenderBlocking
import com.glooboo.umessage.ui.spam.SenderMoves
import com.glooboo.umessage.ui.spam.SenderPlace
import com.glooboo.umessage.ui.spam.UnblockOutcome
import com.glooboo.umessage.ui.spam.unblockOutcome
import com.glooboo.umessage.ui.spam.nameAllowBody
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Provider truth (4.1, 4.3): the chat's sender actions, from the menu and from the chat line. Every action changes
 * where the whole chat lives and sorts it again ([SpamSorting.requestThreads]); each offers Undo:
 *
 * - Block (menu, after its confirm; chat line, at once) and "Report spam" (which equals Block): the block rule, and the
 *   sender's automatic allow rules and memberships go; Undo puts them back (U5, M-SP3).
 * - Unblock: the block rules go. Back in Messages, or still unknown with "Not spam" offered, or still on Android's own
 *   block list (4.2).
 * - "Not spam": a user-made allow rule; a sender name asks once first (4.4); Undo removes only that rule.
 * - Keep: the line about an automatic allow goes for good; the allow stays automatic.
 */
internal class ThreadSpamMoves(
    private val activity: Activity,
    private val scope: CoroutineScope,
    private val snackbar: SnackbarHostState,
    private val repository: () -> SpamRepository,
    private val threadId: () -> Long,
    private val place: SenderPlace = SenderPlace.of(activity, fresh = true),
    /** L3-4: whether the chat is archived: out of Spam it goes to Archived, not to Messages. Off the main thread. */
    private val isArchived: (Long) -> Boolean = { id ->
        runCatching { activity.conversationsDB.getConversationWithThreadId(id)?.isArchived == true }.getOrDefault(false)
    },
) {
    private fun sort() {
        val id = threadId()
        if (id > 0) SpamSorting.requestThreads(activity, listOf(id))
    }

    /** Whether this chat is archived, so that "back in Messages" would be wrong (L3-4). */
    private suspend fun archivedChat(): Boolean {
        val id = threadId().takeIf { it > 0 } ?: return false
        return withContext(Dispatchers.IO) { isArchived(id) }
    }

    private suspend fun offer(message: Int, action: Int = R.string.thread_report_spam_undo, long: Boolean = false) =
        snackbar.showSnackbar(
            message = activity.getString(message),
            actionLabel = activity.getString(action),
            duration = if (long) SnackbarDuration.Long else SnackbarDuration.Short,
        ) == SnackbarResult.ActionPerformed

    /**
     * Block, or "Report spam" when [report]: the whole chat moves to Spam. C-3: when no sender could be read nothing
     * was blocked, and when a user allow rule still names the sender the chat stays: the block is taken back and the
     * snackbar says why, so "Blocked" is never said for a chat that did not move.
     */
    fun block(addresses: List<String>, report: Boolean = false) {
        if (addresses.isEmpty()) return
        scope.launch {
            val note = if (report) RuleNotes.REPORTED_SPAM else SenderBlocking.NOTE
            // D-4: a repository that cannot be had is a failed block, never a crash.
            val repo = runCatching { repository() }.getOrNull()
            val receipts = repo?.let { r -> runCatching { SenderMoves.blockSenders(r, addresses, note) }.getOrNull() }
            if (repo == null || receipts == null || receipts.isEmpty()) {
                snackbar.showSnackbar(activity.getString(R.string.thread_block_failed))
                return@launch
            }
            if (receipts.all { it.stillAllowedBy != null }) {
                SenderMoves.undoBlocks(repo, receipts)
                val why = activity.getString(R.string.pt_block_still_allowed)
                snackbar.showSnackbar(why, duration = SnackbarDuration.Long)
                return@launch
            }
            // D-8: the lock gate's hint for this chat says Spam now, so it is covered before any read.
            threadId().takeIf { it > 0 }?.let { SpamChatGate.Known.note(it, spam = true) }
            wrote()
            if (offer(R.string.thread_block_done)) {
                SenderMoves.undoBlocks(repo, receipts)
                wrote()
            }
        }
    }

    /**
     * After the screen's own write: the shared snapshot is read again (C-17), and the chat is sorted. Its quiet Spam
     * notice has done its job then (4.6: blocked, unblocked or moved).
     */
    private fun wrote() {
        runCatching { ChatSorter.snapshots(activity).invalidate() }
        threadId().takeIf { it > 0 }?.let { BlockedNotifier.cancelChat(activity, it) }
        sort()
    }

    /**
     * "Unblock" (C-4): back in Messages, with Undo; still unknown, with "Not spam"; still on Android's own block list;
     * still blocked by a rule that names more than this sender (a prefix), which is said and nothing changes; or not
     * sure where it went, a neutral text.
     */
    fun unblock(address: String) {
        scope.launch {
            val repo = runCatching { repository() }.getOrNull()
            val removed = repo?.let { r -> runCatching { SenderBlocking.unblock(r, address) }.getOrNull() }
            if (repo == null || removed == null) {
                snackbar.showSnackbar(activity.getString(R.string.thread_unblock_failed))
                return@launch
            }
            wrote()
            val verdict = withContext(Dispatchers.IO) { place.verdict(address) }
            when (unblockOutcome(verdict)) {
                UnblockOutcome.STILL_UNKNOWN ->
                    if (offer(R.string.thread_unblock_done_filtered, R.string.ux8c_not_spam, long = true)) {
                        notSpam(address)
                    }
                UnblockOutcome.BACK_IN_MESSAGES -> undoUnblockIfAsked(
                    repo,
                    removed,
                    activity.getString(
                        if (archivedChat()) R.string.spam_moved_to_archived else R.string.thread_unblock_done,
                    ),
                )
                UnblockOutcome.ANDROID_LIST ->
                    undoUnblockIfAsked(repo, removed, activity.getString(R.string.thread_unblock_done_system))
                UnblockOutcome.OTHER_RULE -> stillBlockedByRule(repo, removed, (verdict as SenderVerdict.Spam).ruleId)
                UnblockOutcome.UNSURE -> if (removed.isEmpty()) {
                    snackbar.showSnackbar(activity.getString(R.string.thread_unblock_failed))
                } else {
                    val text = activity.resources.getQuantityString(R.plurals.blocknum_unblocked, 1, 1)
                    undoUnblockIfAsked(repo, removed, text)
                }
            }
        }
    }

    /**
     * A prefix (or another wide rule) still blocks the sender: what was removed goes back. A prefix is named, with
     * "View rule" opening the rules screen (D-6); another rule gives the plain failure text.
     */
    private suspend fun stillBlockedByRule(repo: SpamRepository, removed: List<SpamRule>, ruleId: Long?) {
        runCatching { repo.restoreRules(removed) }
        wrote()
        val rule = runCatching { repo.rules.first() }.getOrNull()?.firstOrNull { it.id == ruleId }
        if (rule == null || rule.type != RuleType.PREFIX) {
            snackbar.showSnackbar(activity.getString(R.string.thread_unblock_failed), duration = SnackbarDuration.Long)
            return
        }
        // L-7: the reason shows at once, not after an Undo window that is still open.
        snackbar.currentSnackbarData?.dismiss()
        val view = snackbar.showSnackbar(
            // A prefix in the language's digits, isolated whole, as the rule row shows it.
            message = activity.getString(
                R.string.pt_unblock_refused_prefix, ruleValueText(activity, rule.type, rule.pattern),
            ),
            actionLabel = activity.getString(R.string.e_view_rule),
            duration = SnackbarDuration.Long,
        ) == SnackbarResult.ActionPerformed
        if (view) activity.startActivity(RulesActivity.intent(activity, ListKind.BLOCK))
    }

    /** The message of an unblock, with Undo only when something was removed (no Undo that does nothing). */
    private suspend fun undoUnblockIfAsked(repo: SpamRepository, removed: List<SpamRule>, message: String) {
        if (removed.isEmpty()) {
            snackbar.showSnackbar(message, duration = SnackbarDuration.Long)
            return
        }
        val undo = snackbar.showSnackbar(
            message = message,
            actionLabel = activity.getString(R.string.thread_report_spam_undo),
            duration = SnackbarDuration.Long,
        ) == SnackbarResult.ActionPerformed
        if (undo) {
            runCatching { repo.restoreRules(removed) }
            wrote()
        }
    }

    /** "Not spam": a sender name asks once first (4.4: anyone can send texts under a name); a number does not. */
    fun notSpam(address: String) {
        if (!SenderBlocking.isSpoofable(address)) {
            allow(address)
            return
        }
        activity.showConfirmDialog(
            title = activity.getString(R.string.nameallow_title),
            message = nameAllowBody(activity.resources, listOf(address)),
            confirmLabel = activity.getString(R.string.nameallow_allow),
            dismissLabel = activity.getString(R.string.spam_cancel),
        ) { allow(address) }
    }

    private fun allow(address: String) {
        scope.launch {
            val change = runCatching { SenderMoves.notSpam(repository(), listOf(address)) }.getOrElse {
                snackbar.showSnackbar(activity.getString(R.string.thread_always_allow_failed))
                return@launch
            }
            wrote()
            if (change.createdRuleIds.isEmpty()) return@launch
            val done = if (archivedChat()) R.string.spam_moved_to_archived else R.string.thread_always_allow_done
            if (offer(done)) {
                SenderMoves.undoNotSpam(repository(), change)
                wrote()
            }
        }
    }

    /** Keep on an "Allowed" line: acknowledged, so the line does not come back (U15). */
    fun keep(address: String) {
        scope.launch { runCatching { repository().acknowledgeSender(address) } }
    }
}

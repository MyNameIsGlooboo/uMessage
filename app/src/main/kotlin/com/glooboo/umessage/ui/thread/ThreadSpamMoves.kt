package com.glooboo.umessage.ui.thread

import android.app.Activity
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import com.glooboo.umessage.R
import com.glooboo.umessage.spam.SpamRepository
import com.glooboo.umessage.spam.SpamRule
import com.glooboo.umessage.spam.SpamSorting
import com.glooboo.umessage.spam.engine.SenderVerdict
import com.glooboo.umessage.spam.engine.SpamCause
import com.glooboo.umessage.ui.components.showConfirmDialog
import com.glooboo.umessage.ui.spam.RuleNotes
import com.glooboo.umessage.ui.spam.SenderBlocking
import com.glooboo.umessage.ui.spam.SenderMoves
import com.glooboo.umessage.ui.spam.SenderPlace
import com.glooboo.umessage.ui.spam.nameAllowBody
import kotlinx.coroutines.CoroutineScope
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
 *
 * Used only while ProviderTruthFlag is on; [ThreadSenderActions] keeps 0.4.14's actions for the flag off.
 */
internal class ThreadSpamMoves(
    private val activity: Activity,
    private val scope: CoroutineScope,
    private val snackbar: SnackbarHostState,
    private val repository: () -> SpamRepository,
    private val threadId: () -> Long,
    private val place: SenderPlace = SenderPlace.of(activity),
) {
    private fun sort() {
        val id = threadId()
        if (id > 0) SpamSorting.requestThreads(activity, listOf(id))
    }

    private suspend fun offer(message: Int, action: Int = R.string.thread_report_spam_undo, long: Boolean = false) =
        snackbar.showSnackbar(
            message = activity.getString(message),
            actionLabel = activity.getString(action),
            duration = if (long) SnackbarDuration.Long else SnackbarDuration.Short,
        ) == SnackbarResult.ActionPerformed

    /** Block, or "Report spam" when [report]: the whole chat moves to Spam. */
    fun block(addresses: List<String>, report: Boolean = false) {
        if (addresses.isEmpty()) return
        scope.launch {
            val note = if (report) RuleNotes.REPORTED_SPAM else SenderBlocking.NOTE
            val receipts = runCatching { SenderMoves.blockSenders(repository(), addresses, note) }.getOrElse {
                snackbar.showSnackbar(activity.getString(R.string.thread_block_failed))
                return@launch
            }
            sort()
            if (offer(R.string.thread_block_done)) {
                SenderMoves.undoBlocks(repository(), receipts)
                sort()
            }
        }
    }

    fun unblock(address: String) {
        scope.launch {
            val repo = repository()
            val removed = runCatching { SenderBlocking.unblock(repo, address) }.getOrElse {
                snackbar.showSnackbar(activity.getString(R.string.thread_unblock_failed))
                return@launch
            }
            sort()
            val verdict = withContext(Dispatchers.IO) { place.verdict(address) }
            val unknown = (verdict as? SenderVerdict.Spam)?.cause == SpamCause.UNKNOWN
            when {
                unknown -> if (offer(R.string.thread_unblock_done_filtered, R.string.ux8c_not_spam, long = true)) {
                    notSpam(address)
                }
                verdict is SenderVerdict.Spam -> undoUnblockIfAsked(repo, removed, R.string.thread_unblock_done_system)
                else -> undoUnblockIfAsked(repo, removed, R.string.thread_unblock_done)
            }
        }
    }

    private suspend fun undoUnblockIfAsked(repo: SpamRepository, removed: List<SpamRule>, message: Int) {
        val long = message == R.string.thread_unblock_done_system
        if (removed.isEmpty()) {
            // Only Android's own block list named the sender: nothing to undo.
            snackbar.showSnackbar(activity.getString(message), duration = SnackbarDuration.Long)
        } else if (offer(message, long = long)) {
            runCatching { repo.restoreRules(removed) }
            sort()
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
            sort()
            if (change.createdRuleIds.isNotEmpty() && offer(R.string.thread_always_allow_done)) {
                SenderMoves.undoNotSpam(repository(), change)
                sort()
            }
        }
    }

    /** Keep on an "Allowed" line: acknowledged, so the line does not come back (U15). */
    fun keep(address: String) {
        scope.launch { runCatching { repository().acknowledgeSender(address) } }
    }
}

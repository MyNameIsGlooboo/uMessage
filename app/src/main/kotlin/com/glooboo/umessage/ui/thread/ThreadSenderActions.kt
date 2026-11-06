package com.glooboo.umessage.ui.thread

import android.app.Activity
import android.text.TextUtils
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import com.glooboo.umessage.R
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.SpamRepository
import com.glooboo.umessage.spam.SpamRule
import com.glooboo.umessage.spam.UMessage
import com.glooboo.umessage.ui.components.showConfirmDialog
import com.glooboo.umessage.ui.spam.SenderBlocking
import com.glooboo.umessage.ui.spam.SpamRepositoryProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fossify.commons.extensions.isNumberBlocked

/**
 * The thread menu's sender actions: "Block number", "Unblock number" (with "Always allow"), and "Report spam",
 * all through uMessage rules (CM-06), never the phone's own block list. It also follows the rules, so the menu
 * knows whether the single sender is already reported ([reportedSender]) or blocked ([blockedSender]).
 *
 * [recipients] gives the conversation's current people; [lastReceivedSubscriptionId] the SIM the newest received
 * message came in on (-1 when unknown); [onStatusChanged] runs when [reportedSender] or [blockedSender] changed.
 */
class ThreadSenderActions(
    private val activity: Activity,
    private val scope: CoroutineScope,
    private val snackbar: SnackbarHostState,
    private val recipients: () -> ThreadRecipients,
    private val senderName: () -> String,
    private val lastReceivedSubscriptionId: () -> Int,
    private val onStatusChanged: () -> Unit,
) {
    private val repository: SpamRepository get() = SpamRepositoryProvider.get(activity)

    /** Latest uMessage rules, kept so the menu can tell whether the sender is already reported. */
    private var rules: List<SpamRule>? = null

    /** The address the status was last worked out for, so a change of people triggers a new check. */
    private var checkedAddress: String? = null

    /** The single sender's address when a block rule already covers it ("Report spam" would do nothing), else null. */
    var reportedSender: String? = null
        private set

    /** The single sender's address when any uMessage block rule names it, so the menu offers "Unblock"; else null. */
    var blockedSender: String? = null
        private set

    /** The single sender's address when a uMessage allow rule names it (TH-12 chip, no "Always allow"); else null. */
    var allowedSender: String? = null
        private set

    private fun address(): String? = recipients().reportableAddress

    /** Follows the rules, so "Report spam" disappears once the sender is reported and comes back after Undo. */
    fun observeRules() {
        scope.launch {
            runCatching {
                repository.rules.collect { latest ->
                    rules = latest
                    updateStatus()
                }
            }
        }
    }

    /** Works the status out again when the people changed since the last check. */
    fun refreshIfPeopleChanged() {
        if (address() != checkedAddress) updateStatus()
    }

    private fun updateStatus() {
        val current = rules ?: return
        val address = address()
        checkedAddress = address
        scope.launch {
            val (reported, blocked) = if (address == null) {
                false to false
            } else {
                withContext(Dispatchers.Default) {
                    isAlreadyReported(current, address) to SenderBlocking.isBlocked(current, address)
                }
            }
            val allowed = address != null && withContext(Dispatchers.Default) {
                planSpamReport(current, address).allowRulesToRemove.isNotEmpty()
            }
            // Ignore a result that a newer check (other people or rules) has overtaken.
            if (address != address() || current !== rules) return@launch
            val reportedValue = if (reported) address else null
            val blockedValue = if (blocked) address else null
            val allowedValue = if (allowed) address else null
            if (reportedValue != reportedSender || blockedValue != blockedSender || allowedValue != allowedSender) {
                reportedSender = reportedValue
                blockedSender = blockedValue
                allowedSender = allowedValue
                onStatusChanged()
            }
        }
    }

    /**
     * "Block number": after a confirmation, adds a uMessage block rule for every person in the conversation. New
     * texts from them go to Blocked messages, where they can be read and restored. The phone's own block list is not
     * used: Android discards texts from numbers on it before the app sees them. Undo removes the rules again.
     */
    fun askBlock() {
        val numbers = recipients().addresses
        if (numbers.isEmpty()) return
        activity.showConfirmDialog(
            title = activity.getString(R.string.thread_block_title),
            message = activity.getString(R.string.thread_block_message, TextUtils.join(", ", numbers)),
            confirmLabel = activity.getString(R.string.thread_block_confirm),
            dismissLabel = activity.getString(R.string.thread_report_spam_cancel),
        ) {
            scope.launch { block(numbers) }
        }
    }

    private suspend fun block(numbers: List<String>) {
        val repository = repository
        val change = runCatching { SenderBlocking.block(repository, numbers) }.getOrElse {
            snackbar.showSnackbar(activity.getString(R.string.thread_block_failed))
            return
        }
        val outcome = snackbar.showSnackbar(
            message = activity.getString(R.string.thread_block_done),
            actionLabel = if (change.isEmpty) null else activity.getString(R.string.thread_report_spam_undo),
            duration = SnackbarDuration.Short,
        )
        if (outcome == SnackbarResult.ActionPerformed) {
            runCatching { SenderBlocking.undo(repository, change) }
        }
    }

    /**
     * "Unblock number": removes the uMessage block rules for the sender. A number that is also on the phone's own
     * block list stays there (uMessage does not change that list), and the snackbar says so.
     */
    fun unblock() {
        val address = address() ?: return
        scope.launch {
            val repository = repository
            val removed = runCatching { SenderBlocking.unblock(repository, address) }.getOrElse {
                snackbar.showSnackbar(activity.getString(R.string.thread_unblock_failed))
                return@launch
            }
            val onSystemList = withContext(Dispatchers.IO) {
                runCatching { activity.isNumberBlocked(address) }.getOrDefault(false)
            }
            if (onSystemList) {
                offerUndo(repository, removed, R.string.thread_unblock_done_system, SnackbarDuration.Long)
                return@launch
            }
            // Contacts (and every sender in Off mode) reach the inbox; other numbers are still held as unknown
            // senders, so the snackbar says so and offers an explicit "Always allow" instead of promising the inbox.
            val subscriptionId = lastReceivedSubscriptionId()
            val inbox = withContext(Dispatchers.IO) {
                runCatching { SenderBlocking.reachesInbox(UMessage.filter(activity), address, subscriptionId) }
                    .getOrDefault(false)
            }
            if (inbox) {
                offerUndo(repository, removed, R.string.thread_unblock_done, SnackbarDuration.Short)
                return@launch
            }
            val outcome = snackbar.showSnackbar(
                message = activity.getString(R.string.thread_unblock_done_filtered),
                actionLabel = activity.getString(R.string.thread_unblock_always_allow),
                duration = SnackbarDuration.Long,
            )
            if (outcome == SnackbarResult.ActionPerformed) alwaysAllow(repository, address)
        }
    }

    private suspend fun offerUndo(repository: SpamRepository, removed: List<SpamRule>, message: Int, duration: SnackbarDuration) {
        val outcome = snackbar.showSnackbar(
            message = activity.getString(message),
            actionLabel = if (removed.isEmpty()) null else activity.getString(R.string.thread_report_spam_undo),
            duration = duration,
        )
        if (outcome == SnackbarResult.ActionPerformed) {
            runCatching { removed.forEach { repository.addRule(it.copy(id = 0)) } }
        }
    }

    /** "Always allow" after an unblock: an allow rule for the sender, with Undo. */
    private suspend fun alwaysAllow(repository: SpamRepository, address: String) {
        val ruleId = runCatching { SenderBlocking.allow(repository, address) }.getOrElse {
            snackbar.showSnackbar(activity.getString(R.string.thread_always_allow_failed))
            return
        }
        val outcome = snackbar.showSnackbar(
            message = activity.getString(R.string.thread_always_allow_done),
            actionLabel = activity.getString(R.string.thread_report_spam_undo),
            duration = SnackbarDuration.Short,
        )
        if (outcome == SnackbarResult.ActionPerformed) {
            runCatching { repository.removeRule(ruleId) }
        }
    }

    /**
     * "Always allow this sender" (TH-11): an allow rule for the sender; their own block rules go (they would be dead
     * weight under it). Undo puts both back.
     */
    fun allowSender() {
        val address = address() ?: return
        scope.launch {
            val repository = repository
            val change = runCatching { SenderBlocking.alwaysAllow(repository, listOf(address)) }.getOrElse {
                snackbar.showSnackbar(activity.getString(R.string.thread_always_allow_failed))
                return@launch
            }
            val outcome = snackbar.showSnackbar(
                message = activity.getString(R.string.thread_always_allow_done),
                actionLabel = activity.getString(R.string.thread_report_spam_undo),
                duration = SnackbarDuration.Short,
            )
            if (outcome == SnackbarResult.ActionPerformed) {
                runCatching { SenderBlocking.undoAllow(repository, change) }
            }
        }
    }

    /**
     * "Report spam": after a confirmation, adds a uMessage block rule for the sender, the same kind of rule
     * "Block sender" on the Blocked messages screen adds, so new messages from them are held back. Nothing in
     * this conversation is deleted. An earlier "Not spam" allow rule for the sender would win over the block
     * rule, so it is removed; Undo puts everything back.
     */
    fun askReportSpam() {
        val address = address() ?: return
        val name = senderName().ifBlank { address }
        activity.showConfirmDialog(
            title = activity.getString(R.string.thread_report_spam_title),
            message = activity.getString(R.string.thread_report_spam_message, name),
            confirmLabel = activity.getString(R.string.thread_report_spam_confirm),
            dismissLabel = activity.getString(R.string.thread_report_spam_cancel),
        ) {
            scope.launch { reportSpam(address) }
        }
    }

    private suspend fun reportSpam(address: String) {
        val repository = repository
        val result = runCatching {
            val plan = planSpamReport(repository.rules.first(), address)
            plan.allowRulesToRemove.forEach { repository.removeRule(it.id) }
            val addedId = if (plan.alreadyBlocked) {
                null
            } else {
                val note = com.glooboo.umessage.ui.spam.RuleNotes.REPORTED_SPAM
                repository.addRule(SpamRule(kind = ListKind.BLOCK, type = plan.type, pattern = address, note = note))
            }
            addedId to plan.allowRulesToRemove
        }
        val (addedId, allowRules) = result.getOrElse {
            snackbar.showSnackbar(activity.getString(R.string.thread_report_spam_failed))
            return
        }
        val changed = addedId != null || allowRules.isNotEmpty()
        val outcome = snackbar.showSnackbar(
            message = activity.getString(if (addedId != null) R.string.thread_report_spam_done else R.string.thread_report_spam_already),
            actionLabel = if (changed) activity.getString(R.string.thread_report_spam_undo) else null,
            duration = SnackbarDuration.Short,
        )
        if (outcome == SnackbarResult.ActionPerformed) {
            runCatching {
                addedId?.let { repository.removeRule(it) }
                allowRules.forEach { repository.addRule(it.copy(id = 0)) }
            }
        }
    }
}

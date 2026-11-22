package com.glooboo.umessage.ui.thread

import android.app.Activity
import android.text.TextUtils
import androidx.compose.material3.SnackbarHostState
import com.glooboo.umessage.R
import com.glooboo.umessage.spam.SpamRepository
import com.glooboo.umessage.spam.SpamRule
import com.glooboo.umessage.ui.components.showConfirmDialog
import com.glooboo.umessage.ui.spam.SenderBlocking
import com.glooboo.umessage.ui.spam.SpamRepositoryProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The thread menu's sender actions: "Block number", "Unblock number", "Always allow" and "Report spam",
 * all through uMessage rules (CM-06), never the phone's own block list. It also follows the rules, so the menu
 * knows whether the single sender is already reported ([reportedSender]) or blocked ([blockedSender]).
 *
 * [recipients] gives the conversation's current people; [onStatusChanged] runs when [reportedSender] or
 * [blockedSender] changed.
 *
 * Provider truth: each action moves the whole chat ([ThreadSpamMoves], for [threadId]).
 */
class ThreadSenderActions(
    private val activity: Activity,
    private val scope: CoroutineScope,
    private val snackbar: SnackbarHostState,
    private val recipients: () -> ThreadRecipients,
    private val senderName: () -> String,
    private val onStatusChanged: () -> Unit,
    private val threadId: () -> Long = { 0L },
) {
    private val repository: SpamRepository get() = SpamRepositoryProvider.get(activity)

    /** Provider truth (4.1, 4.3): the actions that move the whole chat, also used by the chat line. */
    internal val spamMoves by lazy { ThreadSpamMoves(activity, scope, snackbar, { repository }, threadId) }

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
     * "Block number": after a confirmation, adds a uMessage block rule for every person in the conversation and moves
     * the chat to Spam ([ThreadSpamMoves]). The phone's own block list is not used: Android discards texts from
     * numbers on it before the app sees them. Undo removes the rules again.
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
            spamMoves.block(numbers)
        }
    }

    /**
     * "Unblock number": removes the uMessage block rules for the sender and moves the chat back ([ThreadSpamMoves]).
     */
    fun unblock() {
        val address = address() ?: return
        spamMoves.unblock(address)
    }

    /** "Always allow this sender" (TH-11): "Not spam" for the sender ([ThreadSpamMoves]). */
    fun allowSender() {
        val address = address() ?: return
        spamMoves.notSpam(address)
    }

    /**
     * "Report spam": after a confirmation, blocks the sender as "Block number" does (4.1), so the chat moves to Spam.
     * Nothing in this conversation is deleted.
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
            // Provider truth: "Report spam" equals Block (4.1).
            spamMoves.block(listOf(address), report = true)
        }
    }
}

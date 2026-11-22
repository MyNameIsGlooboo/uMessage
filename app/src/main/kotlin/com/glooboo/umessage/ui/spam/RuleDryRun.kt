package com.glooboo.umessage.ui.spam

import android.content.Context
import android.provider.Telephony.Sms
import com.glooboo.umessage.helpers.SpamReaders
import com.glooboo.umessage.spam.IncomingMessage
import com.glooboo.umessage.spam.SpamRule
import com.glooboo.umessage.spam.engine.NumberNormalizer
import com.glooboo.umessage.spam.engine.RuleMatcher
import com.glooboo.umessage.spam.engine.defaultRegion
import com.glooboo.umessage.spam.engine.subscriptionRegion
import com.glooboo.umessage.ui.lock.LockScope
import org.fossify.commons.extensions.appLockManager

/**
 * One message the "test this rule" preview tries the rule on. Held only while the scan runs; never stored or
 * logged.
 */
class DryRunMessage(val address: String, val body: String, val subscriptionId: Int)

/** What the rule would match in one place (the inbox or Blocked history). [senders] is empty when they are hidden. */
data class DryRunPart(val scanned: Int = 0, val matched: Int = 0, val senders: List<String> = emptyList())

data class RuleDryRunResult(val inbox: DryRunPart = DryRunPart(), val blocked: DryRunPart = DryRunPart()) {
    val isEmpty: Boolean get() = inbox.scanned == 0 && blocked.scanned == 0
}

/** State of the preview in the rule sheet. */
sealed interface RulePreviewState {
    data object Idle : RulePreviewState
    data object Loading : RulePreviewState
    data class Done(val result: RuleDryRunResult) : RulePreviewState
    data object Failed : RulePreviewState
}

/**
 * The pure counting behind "Test this rule": the same [RuleMatcher] the filter uses, over a capped list of
 * messages.
 */
object RuleDryRun {
    /** Newest messages tried in each place, so a big inbox never makes the preview slow. */
    const val MAX_SCAN = 100
    const val MAX_EXAMPLES = 3

    /** Received texts read at most to find those of one place (Messages or Spam), so a big store stays quick. */
    const val MAX_READ = 5_000

    /**
     * How many of the newest [cap] [messages] (given newest first) [rule] matches, with up to [MAX_EXAMPLES] different
     * senders as examples when [showSenders]. A rule for one SIM only sees that SIM's messages, as in the filter.
     * [keepGoing] is asked between messages so a stale scan can stop early.
     */
    fun scan(
        matcher: RuleMatcher,
        rule: SpamRule,
        messages: List<DryRunMessage>,
        showSenders: Boolean = true,
        cap: Int = MAX_SCAN,
        keepGoing: () -> Boolean = { true },
    ): DryRunPart {
        var scanned = 0
        var matched = 0
        val senders = LinkedHashSet<String>()
        for (message in messages) {
            if (scanned >= cap || !keepGoing()) break
            scanned++
            if (hits(matcher, rule, message)) {
                matched++
                if (showSenders && senders.size < MAX_EXAMPLES && message.address.isNotBlank()) {
                    senders += message.address
                }
            }
        }
        return DryRunPart(scanned, matched, senders.toList())
    }

    /** Whether [rule] matches [message]; a rule for one SIM never sees another SIM's messages. */
    private fun hits(matcher: RuleMatcher, rule: SpamRule, message: DryRunMessage): Boolean {
        if (rule.subscriptionId != null && rule.subscriptionId != message.subscriptionId) return false
        return runCatching {
            matcher.matches(rule, IncomingMessage(message.address, message.body, message.subscriptionId))
        }.getOrDefault(false)
    }
}

/**
 * H-16: the preview without the example senders of its Spam part while they may not show ([visible] false: the app
 * lock covers Spam and is locked), so a preview made before the lock locked again does not keep them on screen.
 */
fun RulePreviewState.hidingSpamSenders(visible: Boolean): RulePreviewState =
    if (this is RulePreviewState.Done && !visible && result.blocked.senders.isNotEmpty()) {
        copy(result = result.copy(blocked = result.blocked.copy(senders = emptyList())))
    } else {
        this
    }

/** Where the preview reads the inbox and the matcher from. Blocking; call off the main thread. */
interface RulePreviewSource {
    fun matcher(): RuleMatcher

    /**
     * The newest received texts, newest first, at most [limit]. Empty when the inbox cannot be read. Provider truth
     * (section 5): the texts of the chats in Messages only; those of Spam chats are [spam].
     */
    fun inbox(limit: Int): List<DryRunMessage>

    /**
     * Provider truth (section 5): the newest received texts of the chats in Spam, newest first, at most [limit]; they
     * stand for the Blocked history, since spam now lives in the phone's store. Empty when they cannot be read.
     */
    fun spam(limit: Int): List<DryRunMessage> = emptyList()

    /** False while the app lock covers Blocked and is locked: counts stay, example senders are not shown. */
    fun blockedSendersVisible(): Boolean
}

object NoRulePreviewSource : RulePreviewSource {
    override fun matcher() = RuleMatcher(NumberNormalizer { "GB" })
    override fun inbox(limit: Int): List<DryRunMessage> = emptyList()
    override fun blockedSendersVisible() = true
}

class AndroidRulePreviewSource(context: Context) : RulePreviewSource {
    private val app = context.applicationContext

    override fun matcher() = RuleMatcher(
        NumberNormalizer { defaultRegion(app) },
        regionForSubscription = { subscriptionRegion(app, it) },
    )

    override fun inbox(limit: Int): List<DryRunMessage> = runCatching {
        // A failed read of Spam reads nothing, so a Spam sender is never shown as an inbox example.
        val spam = SpamReaders.threadIds(app)
        received(limit) { it !in spam }
    }.getOrDefault(emptyList())

    override fun spam(limit: Int): List<DryRunMessage> = runCatching {
        val spam = SpamReaders.threadIds(app)
        if (spam.isEmpty()) emptyList() else received(limit) { it in spam }
    }.getOrDefault(emptyList())

    /**
     * The newest received texts whose chat [keep] says yes to, newest first, at most [limit], among the newest
     * [RuleDryRun.MAX_READ] received texts.
     */
    private fun received(limit: Int, keep: (threadId: Long) -> Boolean): List<DryRunMessage> {
        val out = ArrayList<DryRunMessage>()
        app.contentResolver.query(
            Sms.Inbox.CONTENT_URI,
            arrayOf(Sms.ADDRESS, Sms.BODY, Sms.SUBSCRIPTION_ID, Sms.THREAD_ID),
            null,
            null,
            "${Sms.DATE} DESC",
        )?.use { cursor ->
            val address = cursor.getColumnIndexOrThrow(Sms.ADDRESS)
            val body = cursor.getColumnIndexOrThrow(Sms.BODY)
            val sub = cursor.getColumnIndexOrThrow(Sms.SUBSCRIPTION_ID)
            val thread = cursor.getColumnIndexOrThrow(Sms.THREAD_ID)
            var read = 0
            while (out.size < limit && read++ < RuleDryRun.MAX_READ && cursor.moveToNext()) {
                if (!keep(cursor.getLong(thread))) continue
                out += DryRunMessage(
                    cursor.getString(address).orEmpty(), cursor.getString(body).orEmpty(), cursor.getInt(sub)
                )
            }
        }
        return out
    }

    override fun blockedSendersVisible(): Boolean =
        runCatching { !(LockScope.blockedLocked(app) && app.appLockManager.isLocked()) }.getOrDefault(false)
}

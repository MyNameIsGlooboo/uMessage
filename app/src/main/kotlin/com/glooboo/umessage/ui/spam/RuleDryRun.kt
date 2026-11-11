package com.glooboo.umessage.ui.spam

import android.content.Context
import android.provider.Telephony.Sms
import com.glooboo.umessage.spam.BlockedMessage
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

    fun blockedMessages(blocked: List<BlockedMessage>, cap: Int = MAX_SCAN): List<DryRunMessage> =
        blocked.asSequence()
            .filter { it.hasText }
            .sortedByDescending { it.receivedAt }
            .take(cap)
            .map { DryRunMessage(it.address, it.body, it.subscriptionId) }
            .toList()
}

/** Where the preview reads the inbox and the matcher from. Blocking; call off the main thread. */
interface RulePreviewSource {
    fun matcher(): RuleMatcher

    /** The newest received texts, newest first, at most [limit]. Empty when the inbox cannot be read. */
    fun inbox(limit: Int): List<DryRunMessage>

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
        val out = ArrayList<DryRunMessage>()
        app.contentResolver.query(
            Sms.Inbox.CONTENT_URI,
            arrayOf(Sms.ADDRESS, Sms.BODY, Sms.SUBSCRIPTION_ID),
            null,
            null,
            "${Sms.DATE} DESC",
        )?.use { cursor ->
            val address = cursor.getColumnIndexOrThrow(Sms.ADDRESS)
            val body = cursor.getColumnIndexOrThrow(Sms.BODY)
            val sub = cursor.getColumnIndexOrThrow(Sms.SUBSCRIPTION_ID)
            while (out.size < limit && cursor.moveToNext()) {
                out += DryRunMessage(
                    cursor.getString(address).orEmpty(), cursor.getString(body).orEmpty(), cursor.getInt(sub)
                )
            }
        }
        out
    }.getOrDefault(emptyList())

    override fun blockedSendersVisible(): Boolean =
        runCatching { !(LockScope.blockedLocked(app) && app.appLockManager.isLocked()) }.getOrDefault(false)
}

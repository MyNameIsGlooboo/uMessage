package com.glooboo.umessage.spam.engine

import com.glooboo.umessage.helpers.CodeMasker
import com.glooboo.umessage.helpers.OtpCodeExtractor
import com.glooboo.umessage.spam.ForwardFormat
import com.glooboo.umessage.spam.ForwardRule
import com.glooboo.umessage.spam.ForwardSim
import com.glooboo.umessage.spam.IncomingMessage
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.SpamRule
import java.time.Instant
import java.time.ZoneId

/** A message that reached the inbox, as the forward rules see it. */
data class ForwardCandidate(
    val address: String,
    val body: String,
    val subscriptionId: Int,
    val isMms: Boolean = false,
    val receivedAt: Long = System.currentTimeMillis(),
    /** False for a message the user sent. Only received messages are ever forwarded. */
    val incoming: Boolean = true,
)

/** One SMS to send: [text] to [target], from [subscriptionId] (null = the phone's default SMS SIM). */
data class PlannedForward(val ruleId: Long, val target: String, val text: String, val subscriptionId: Int?)

enum class ForwardSkip {
    /** The message is a verification code and the rule does not include codes. */
    CODE_EXCLUDED,

    /** The message is itself a forward (it starts with the forward prefix). */
    LOOP_FORWARD,

    /** The target is the number the message came from. */
    LOOP_SENDER,

    /** The rule wants SIM 1 or SIM 2, which is not in the phone: the receiving SIM is used instead. */
    SIM_FALLBACK,

    /** The rule already forwarded [ForwardRateLimit.MAX_PER_RULE_PER_HOUR] messages in the last hour (BR-35). */
    RATE_LIMITED,
}

data class SkippedForward(val ruleId: Long, val reason: ForwardSkip, val target: String? = null)

data class ForwardPlan(val sends: List<PlannedForward>, val skipped: List<SkippedForward>) {
    companion object {
        val NONE = ForwardPlan(emptyList(), emptyList())
    }
}

/**
 * BR-35: a cap on forwards per rule, so a rule that matches a flood (or a mistyped rule) cannot send hundreds of SMS
 * that the user pays for. Pure: the caller supplies how many forwards each rule already made in the window.
 */
object ForwardRateLimit {
    const val MAX_PER_RULE_PER_HOUR = 20
    const val WINDOW_MS = 60L * 60 * 1000

    /**
     * Splits [sends] into those allowed and those over the cap, in order. [alreadySent] counts a rule's forwards in
     * the last [WINDOW_MS] (missing = 0); each allowed send counts towards the cap for the rest of [sends].
     */
    fun split(
        sends: List<PlannedForward>,
        alreadySent: Map<Long, Int>,
        max: Int = MAX_PER_RULE_PER_HOUR,
    ): Pair<List<PlannedForward>, List<PlannedForward>> {
        val used = alreadySent.toMutableMap()
        val allowed = mutableListOf<PlannedForward>()
        val limited = mutableListOf<PlannedForward>()
        for (send in sends) {
            val count = used[send.ruleId] ?: 0
            if (count >= max) {
                limited += send
            } else {
                used[send.ruleId] = count + 1
                allowed += send
            }
        }
        return allowed to limited
    }
}

/**
 * Decides which forwards an inbox message triggers. Pure logic: no Android, no sending.
 *
 * Loop guard (always on, not a setting): a message that is itself a forward is never forwarded, a message the user
 * sent is never forwarded, and nothing is forwarded to the number it came from. Verification codes
 * ([isCode], by default [looksLikeCode]) are skipped unless a rule includes them, and then go out with the
 * code hidden when [codeMask] is set. A target matched by
 * several rules gets the message once.
 */
class ForwardPlanner(
    private val matcher: RuleMatcher,
    private val isCode: (String) -> Boolean = { looksLikeCode(it) },
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    /** The subscription id of the SIM in a 1-based slot, null when that slot is empty. */
    private val simInSlot: (Int) -> Int? = { null },
    /**
     * Hides the code of a message that holds one when a rule includes codes, or null to forward it in full. Read for
     * every message, so a change of the setting applies at once.
     */
    private val codeMask: () -> ((String) -> String)? = { null },
) {

    fun plan(message: ForwardCandidate, rules: List<ForwardRule>, senderDisplay: (String) -> String): ForwardPlan {
        if (!message.incoming) return ForwardPlan.NONE
        val matching = rules.filter { it.enabled && it.targets.isNotEmpty() && matches(it, message) }
        if (matching.isEmpty()) return ForwardPlan.NONE
        if (ForwardFormat.isForward(message.body)) {
            return ForwardPlan(emptyList(), matching.map { SkippedForward(it.id, ForwardSkip.LOOP_FORWARD) })
        }

        val skipped = mutableListOf<SkippedForward>()
        val sends = mutableListOf<PlannedForward>()
        val code = isCode(message.body)
        var text: String? = null
        var maskedText: String? = null
        val masker = if (code) runCatching { codeMask() }.getOrNull() else null
        for (rule in matching) {
            if (code && !rule.includeCodes) {
                skipped += SkippedForward(rule.id, ForwardSkip.CODE_EXCLUDED)
                continue
            }
            val from = sendingSim(rule, message) { skipped += SkippedForward(rule.id, ForwardSkip.SIM_FALLBACK) }
            for (target in rule.targets.map { it.trim() }.filter { it.isNotEmpty() }) {
                if (matcher.sameNumber(target, message.address, message.subscriptionId)) {
                    skipped += SkippedForward(rule.id, ForwardSkip.LOOP_SENDER, target)
                    continue
                }
                if (sends.any { matcher.sameNumber(it.target, target, message.subscriptionId) }) continue
                val body = if (masker != null) {
                    maskedText ?: ForwardFormat.format(
                        senderDisplay(message.address), failClosed(masker, message.body), message.isMms,
                    ).also { maskedText = it }
                } else {
                    text ?: ForwardFormat.format(senderDisplay(message.address), message.body, message.isMms)
                        .also { text = it }
                }
                sends += PlannedForward(rule.id, target, body, from)
            }
        }
        return ForwardPlan(sends, skipped)
    }

    /** Whether [rule]'s conditions (sender, text, time of day, receiving SIM) all hold for [message]. */
    fun matches(rule: ForwardRule, message: ForwardCandidate): Boolean {
        if (rule.senderType == null && rule.bodyType == null) return false
        if (rule.subscriptionId != null && rule.subscriptionId != message.subscriptionId) return false
        val minute = minuteOfDay(message.receivedAt)
        if (rule.hasWindow && !inWindow(rule.windowStart!!, rule.windowEnd!!, minute)) return false
        val incoming =
            IncomingMessage(message.address, message.body, message.subscriptionId, message.isMms, message.receivedAt)
        val numbers = matcher.numberReaders(message.subscriptionId)
        // ALLOW kind: a keyword matches the text as written (letter forms folded), without the leetspeak reading
        // that BLOCK keywords use, so a forward rule does not fire on look-alike words.
        rule.senderType?.let { type ->
            val sender = SpamRule(kind = ListKind.ALLOW, type = type, pattern = rule.senderPattern)
            if (!matcher.matches(sender, incoming, numbers)) {
                return false
            }
        }
        rule.bodyType?.let { type ->
            val body = SpamRule(kind = ListKind.ALLOW, type = type, pattern = rule.bodyPattern)
            if (!matcher.matches(body, incoming, numbers)) {
                return false
            }
        }
        return true
    }

    private fun minuteOfDay(time: Long): Int {
        val local = Instant.ofEpochMilli(time).atZone(runCatching { zone() }.getOrDefault(ZoneId.systemDefault()))
        return local.hour * 60 + local.minute
    }

    private inline fun sendingSim(rule: ForwardRule, message: ForwardCandidate, onFallback: () -> Unit): Int? {
        val receiving = message.subscriptionId.takeIf { it >= 0 }
        val slot = when (rule.sendFrom) {
            ForwardSim.SAME -> return receiving
            ForwardSim.SIM1 -> 1
            ForwardSim.SIM2 -> 2
        }
        val sub = runCatching { simInSlot(slot) }.getOrNull()
        if (sub == null) onFallback()
        return sub ?: receiving
    }

    companion object {
        const val MINUTES_PER_DAY = 24 * 60

        /**
         * M3: masking fails closed. The message was judged a code; if [masker] left it unchanged (it could not locate
         * the code, or it threw), every 4 to 8 digit run is hidden rather than forwarding the code in clear. A code
         * written in groups ("48 29 13") is hidden in both cases.
         */
        fun failClosed(masker: (String) -> String, body: String): String {
            val masked = runCatching { masker(body) }.getOrNull()
            val hidden = if (masked != null && masked != body) masked else CodeMasker.maskDigitRuns(body)
            return CodeMasker.maskSpacedCodes(hidden)
        }

        /**
         * Whether forwarding treats [body] as a verification code: the spam filter's detector says so, or the
         * notification's "Copy code" finds one. Either is enough, so a message the app offers a code for is never
         * forwarded in clear by a rule without codes ("bank verification code is 48 29 13 ref 7766554").
         */
        fun looksLikeCode(body: String): Boolean =
            OtpDetector.looksLikeOtp(body) || runCatching { OtpCodeExtractor.extract(body) != null }.getOrDefault(true)

        /**
         * [start] inclusive, [end] exclusive; a window that ends before it starts runs over midnight. Equal = all day.
         */
        fun inWindow(start: Int, end: Int, minute: Int): Boolean = when {
            start == end -> true
            start < end -> minute in start until end
            else -> minute >= start || minute < end
        }
    }
}

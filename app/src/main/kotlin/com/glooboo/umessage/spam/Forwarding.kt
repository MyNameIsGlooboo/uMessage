package com.glooboo.umessage.spam

import kotlinx.coroutines.flow.Flow

/** Which SIM a forward is sent from. */
enum class ForwardSim {
    /** The SIM the message arrived on. */
    SAME,

    /** The SIM in slot 1. */
    SIM1,

    /** The SIM in slot 2. */
    SIM2,
}

/**
 * A rule that forwards matching inbox messages as SMS to up to [MAX_TARGETS] phone numbers.
 *
 * Conditions: a sender condition ([senderType] NUMBER, PREFIX or SENDER_ID with [senderPattern]) and/or a text
 * condition ([bodyType] KEYWORD or REGEX with [bodyPattern]); at least one of them is set. Both must match when both
 * are set. [windowStart] / [windowEnd] (minutes after local midnight, both set or both null) limit it to a time of
 * day; a window that ends before it starts runs over midnight. [subscriptionId] limits it to messages received on one
 * SIM (null = both).
 *
 * Only messages that reached the inbox are forwarded, never blocked spam. Verification codes are skipped unless
 * [includeCodes] is on, and the loop guard in [ForwardPlanner] always applies.
 */
data class ForwardRule(
    val id: Long = 0,
    val senderType: RuleType? = null,
    val senderPattern: String = "",
    val bodyType: RuleType? = null,
    val bodyPattern: String = "",
    val windowStart: Int? = null,
    val windowEnd: Int? = null,
    val subscriptionId: Int? = null,
    val targets: List<String> = emptyList(),
    val sendFrom: ForwardSim = ForwardSim.SAME,
    val includeCodes: Boolean = false,
    val enabled: Boolean = true,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
) {
    val hasWindow: Boolean get() = windowStart != null && windowEnd != null

    companion object {
        const val MAX_TARGETS = 3
        val SENDER_TYPES = listOf(RuleType.NUMBER, RuleType.PREFIX, RuleType.SENDER_ID)
        val BODY_TYPES = listOf(RuleType.KEYWORD, RuleType.REGEX)
    }
}

/** Storage of [ForwardRule]s. Changes are made only after the UI's app-lock check. */
interface ForwardRepository {
    val rules: Flow<List<ForwardRule>>

    /** Blocking read for the receivers; empty when the database cannot be read. */
    fun currentRules(): List<ForwardRule>

    suspend fun add(rule: ForwardRule): Long
    suspend fun update(rule: ForwardRule)
    suspend fun setEnabled(id: Long, enabled: Boolean)
    suspend fun remove(id: Long)
}

/** The text a forward carries. The prefix is fixed (never translated) so the loop guard can always recognise it. */
object ForwardFormat {
    const val PREFIX = "[from "

    /** `[from <sender>] <text>`, plus [pictureNote] for an MMS. */
    fun format(senderDisplay: String, body: String, isMms: Boolean, pictureNote: String = PICTURE_NOT_FORWARDED): String {
        val text = body.trim()
        val sender = senderDisplay.replace(']', ')').replace('\n', ' ').trim().ifEmpty { "?" }
        val content = when {
            !isMms -> text
            text.isEmpty() -> pictureNote
            else -> "$text $pictureNote"
        }
        return "$PREFIX$sender] $content"
    }

    /** True for a message that is itself a forward (from this app on another phone, or a loop back to this one). */
    fun isForward(body: String): Boolean = body.trimStart().startsWith(PREFIX)

    const val PICTURE_NOT_FORWARDED = "[picture not forwarded]"
}

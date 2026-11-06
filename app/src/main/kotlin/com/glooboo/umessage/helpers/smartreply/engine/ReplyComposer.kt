package com.glooboo.umessage.helpers.smartreply.engine

import java.util.Random

/**
 * Rule 5 of `docs/smart-engine.md`: the chips of one classified question. YES_NO and INVITE give one pick each from
 * `yes`, `no` and `later`, in that order; WHEN, WHERE and REQUEST three different picks; CHOICE the two options and
 * one `either` pick. Picks are seeded (the message id), so one message always gets the same chips; seed 0 gives the
 * first entries in file order. For a [Register.FORMAL] sender the table's `formal` pool replaces the matching pool
 * when it has enough entries (`invite.yes` before `yes` for an invitation). Pure JVM.
 */
internal object ReplyComposer {

    private const val PICKS = 3
    private const val SEED_STEP = 31L
    private const val SALT_YES = 1L
    private const val SALT_NO = 2L
    private const val SALT_LATER = 3L
    private val GROUPS = listOf("yes" to SALT_YES, "no" to SALT_NO, "later" to SALT_LATER)

    fun compose(index: EngineIndex, classification: Classification, register: Register, seed: Long): List<String> {
        val table = index.table
        val formal = if (register == Register.FORMAL) table.formal else emptyMap()
        val replies = when (classification.type) {
            QuestionType.YES_NO -> table.yesNo.let { eachOnce(formal, "", listOf(it.yes, it.no, it.later), seed) }
            QuestionType.INVITE ->
                table.invite.let { eachOnce(formal, "invite.", listOf(it.yes, it.no, it.later), seed) }
            QuestionType.WHEN -> several(pool(formal, "when", table.whenBlock.replies, PICKS), seed)
            QuestionType.WHERE -> several(pool(formal, "where", table.whereBlock.replies, PICKS), seed)
            QuestionType.REQUEST -> several(pool(formal, "request", table.request.replies, PICKS), seed)
            QuestionType.CHOICE ->
                classification.options + listOfNotNull(one(pool(formal, "either", table.choice.either, 1), seed, 0))
        }
        return replies.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    /** One pick of each of the `yes`, `no` and `later` [pools], in that order. */
    private fun eachOnce(formal: Map<String, List<String>>, prefix: String, pools: List<List<String>>, seed: Long) =
        GROUPS.mapIndexedNotNull { i, (name, salt) -> one(pool(formal, name, pools[i], 1, prefix), seed, salt) }

    /** The formal pool of [name] (as `prefix + name` first, then plain) when it has [needed] entries, else [base]. */
    private fun pool(
        formal: Map<String, List<String>>,
        name: String,
        base: List<String>,
        needed: Int,
        prefix: String = "",
    ): List<String> =
        listOf(prefix + name, name).firstNotNullOfOrNull { formal[it]?.takeIf { pool -> pool.size >= needed } } ?: base

    private fun one(pool: List<String>, seed: Long, salt: Long): String? = when {
        pool.isEmpty() -> null
        seed == 0L -> pool.first()
        else -> pool[Random(seed * SEED_STEP + salt).nextInt(pool.size)]
    }

    private fun several(pool: List<String>, seed: Long): List<String> =
        if (seed == 0L || pool.size <= PICKS) pool.take(PICKS) else pool.shuffled(Random(seed)).take(PICKS)
}

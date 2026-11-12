package com.glooboo.umessage.ui.spam

import android.content.res.Resources
import com.glooboo.umessage.R
import com.glooboo.umessage.spam.SpamRule

/** How long ago a rule last blocked a message, in the largest whole unit ([count] is 0 for [RuleHitUnit.NOW]). */
enum class RuleHitUnit { NOW, MINUTES, HOURS, DAYS, MONTHS, YEARS }

data class RuleHitAge(val unit: RuleHitUnit, val count: Int)

/** spam-23: "Blocked 12 messages, last 3 days ago" on a rule's row. Nothing is said for a rule that never blocked. */
object RuleHits {
    private const val MINUTE_MS = 60_000L
    private const val HOUR_MS = 60 * MINUTE_MS
    private const val DAY_MS = 24 * HOUR_MS
    private const val DAYS_PER_MONTH = 30L
    private const val DAYS_PER_YEAR = 365L
    private const val MONTHS_AS_DAYS = 360L

    /** The age of [lastHit] at [now]; null when it never matched (0). A time in the future counts as just now. */
    fun age(lastHit: Long, now: Long): RuleHitAge? {
        if (lastHit <= 0L) return null
        val ms = now - lastHit
        val days = ms / DAY_MS
        return when {
            ms < MINUTE_MS -> RuleHitAge(RuleHitUnit.NOW, 0)
            ms < HOUR_MS -> RuleHitAge(RuleHitUnit.MINUTES, (ms / MINUTE_MS).toInt())
            ms < DAY_MS -> RuleHitAge(RuleHitUnit.HOURS, (ms / HOUR_MS).toInt())
            days < DAYS_PER_MONTH -> RuleHitAge(RuleHitUnit.DAYS, days.toInt())
            days < MONTHS_AS_DAYS -> RuleHitAge(RuleHitUnit.MONTHS, (days / DAYS_PER_MONTH).toInt())
            else -> RuleHitAge(RuleHitUnit.YEARS, (days / DAYS_PER_YEAR).toInt().coerceAtLeast(1))
        }
    }

    /** The row line for [rule], or null when it has not blocked anything. */
    fun text(resources: Resources, rule: SpamRule, now: Long = System.currentTimeMillis()): String? {
        if (rule.hitCount <= 0) return null
        val blocked = resources.getQuantityString(R.plurals.rdec_hits_blocked, rule.hitCount, rule.hitCount)
        val age = age(rule.lastHit, now) ?: return blocked
        val ago = when (age.unit) {
            RuleHitUnit.NOW -> resources.getString(R.string.rdec_age_now)
            RuleHitUnit.MINUTES -> resources.getQuantityString(R.plurals.rdec_age_minutes, age.count, age.count)
            RuleHitUnit.HOURS -> resources.getQuantityString(R.plurals.rdec_age_hours, age.count, age.count)
            RuleHitUnit.DAYS -> resources.getQuantityString(R.plurals.rdec_age_days, age.count, age.count)
            RuleHitUnit.MONTHS -> resources.getQuantityString(R.plurals.rdec_age_months, age.count, age.count)
            RuleHitUnit.YEARS -> resources.getQuantityString(R.plurals.rdec_age_years, age.count, age.count)
        }
        return resources.getString(R.string.rdec_hits_last, blocked, ago)
    }
}

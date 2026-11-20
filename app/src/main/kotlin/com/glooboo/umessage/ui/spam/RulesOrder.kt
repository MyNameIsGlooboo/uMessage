package com.glooboo.umessage.ui.spam

import android.content.Context
import com.glooboo.umessage.helpers.ProviderTruthFlag
import com.glooboo.umessage.spam.SpamRule

/**
 * main-23: the order of the Rules lists. [DEFAULT] is the order the lists always had (by the time the rule was
 * added); [NEWEST] puts the rule saved last first, whatever its added time, so a rule just restored or imported is
 * at the top. With [ProviderTruthFlag] on, the rules the filter made itself (a sender allowed or blocked by one of the
 * user's words, H4) come after the user's own, in either order (4.5).
 */
enum class RulesOrder {
    DEFAULT,
    NEWEST,
    ;

    fun apply(rules: List<SpamRule>): List<SpamRule> {
        val sorted = when (this) {
            DEFAULT -> rules.sortedByDescending { it.createdAt }
            NEWEST -> rules.sortedByDescending { it.id }
        }
        return if (ProviderTruthFlag.enabled) sorted.sortedBy { RuleNotes.isMadeByFilter(it.note) } else sorted
    }
}

/** Remembers the chosen [RulesOrder]. */
interface RulesOrderStore {
    fun load(): RulesOrder
    fun save(order: RulesOrder)
}

class MemoryRulesOrderStore(private var order: RulesOrder = RulesOrder.DEFAULT) : RulesOrderStore {
    override fun load(): RulesOrder = order
    override fun save(order: RulesOrder) {
        this.order = order
    }
}

class AndroidRulesOrderStore(private val context: Context) : RulesOrderStore {
    override fun load(): RulesOrder = runCatching {
        RulesOrder.valueOf(context.getSharedPreferences(PREFS, 0).getString(KEY, null).orEmpty())
    }.getOrDefault(RulesOrder.DEFAULT)

    override fun save(order: RulesOrder) {
        runCatching { context.getSharedPreferences(PREFS, 0).edit().putString(KEY, order.name).apply() }
    }

    private companion object {
        const val PREFS = "spamless_rules_ui"
        const val KEY = "order"
    }
}

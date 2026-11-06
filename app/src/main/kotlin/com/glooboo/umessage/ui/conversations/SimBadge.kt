package com.glooboo.umessage.ui.conversations

import android.content.Context
import com.glooboo.umessage.R
import com.glooboo.umessage.helpers.SimNames
import com.glooboo.umessage.helpers.composerPrefs
import com.glooboo.umessage.ui.spam.SimInfo

/** The SIM badge of an inbox row, and the names of the SIMs on the search page's tiles. */
internal object SimBadge {

    /** The SIM a conversation without a choice of its own uses: the app's setting, else the phone's. */
    fun defaultSubscription(appDefault: Int, systemDefault: Int): Int =
        if (appDefault >= 0) appDefault else systemDefault

    /**
     * The slot to badge a row with: only when the row's newest message used a SIM other than [defaultSubscriptionId],
     * so the badge marks the exception. Null when it used the default or no known SIM.
     */
    fun slotToShow(subscriptionId: Int, sims: List<SimInfo>, defaultSubscriptionId: Int): Int? =
        if (subscriptionId <= 0 || subscriptionId == defaultSubscriptionId) {
            null
        } else {
            sims.firstOrNull { it.subscriptionId == subscriptionId }?.slot
        }

    private val PLACEHOLDER_CARRIERS =
        setOf("unknown", "no service", "no sim", "searching for service", "emergency calls only")

    /** The nickname the user gave the SIM, else [fallback] ("SIM 2"). */
    fun nameOf(nickname: String?, fallback: String): String = nickname?.trim()?.ifEmpty { null } ?: fallback

    /** The carrier name as the phone reports it, trimmed; null when blank or a placeholder such as "No service". */
    fun carrierName(carrier: String?): String? =
        carrier?.trim()?.takeIf { it.isNotEmpty() && it.lowercase() !in PLACEHOLDER_CARRIERS }

    /** The nickname, else the carrier name, else [fallback] ("SIM 2"). */
    fun nameOf(nickname: String?, carrier: String?, fallback: String): String =
        nameOf(nickname, carrierName(carrier) ?: fallback)

    fun displayName(context: Context, sim: SimInfo): String = nameOf(
        SimNames(context.composerPrefs).get(sim.slot, sim.carrier)?.nickname,
        sim.carrier,
        context.getString(R.string.convlist_sim, sim.slot),
    )
}

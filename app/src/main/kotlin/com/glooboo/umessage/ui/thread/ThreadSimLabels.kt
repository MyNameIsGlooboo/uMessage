package com.glooboo.umessage.ui.thread

import android.telephony.SubscriptionInfo
import com.glooboo.umessage.helpers.ComposerPrefs
import com.glooboo.umessage.helpers.SimName
import com.glooboo.umessage.helpers.SimNames

/**
 * What a date header shows for SIM number [index] (0 based): the nickname the user gave it (I34), else its number.
 * [twoSims] is false on a phone with one SIM, where the header shows no SIM at all and a nickname would only confuse.
 */
fun simHeaderLabel(index: Int, name: SimName?, twoSims: Boolean): String =
    name?.nickname?.takeIf { twoSims && it.isNotEmpty() } ?: "${index + 1}"

/**
 * [simHeaderLabel] for each active SIM, by subscription id. Two SIMs with the same nickname would look like one SIM
 * in the headers, so then the numbers stay.
 */
fun simHeaderLabels(active: List<SubscriptionInfo>?, prefs: ComposerPrefs): Map<Int, String> {
    val sims = active.orEmpty()
    val names = SimNames(prefs)
    val labels = sims.mapIndexed { index, info ->
        val name = names.get(info.simSlotIndex + 1, info.carrierName?.toString().orEmpty())
        info.subscriptionId to simHeaderLabel(index, name, twoSims = sims.size > 1)
    }
    if (labels.map { it.second }.distinct().size < labels.size) {
        return sims.mapIndexed { index, info -> info.subscriptionId to "${index + 1}" }.toMap()
    }
    return labels.toMap()
}

package com.glooboo.umessage.helpers

/**
 * Picks the SIM (subscription id) to send from, in the same order as the thread screen: the SIM saved for the
 * number, then the SIM the last message was received on, then the app default SIM, then the system default.
 * Every candidate is a subscription id, never a list index. Returns null when none of them is an active SIM.
 */
fun pickSendSubscriptionId(
    activeSubscriptionIds: List<Int>,
    savedForNumber: Int?,
    lastReceivedOn: Int?,
    appDefault: Int,
    systemDefault: Int,
): Int? {
    val candidates = listOf(
        savedForNumber,
        lastReceivedOn,
        appDefault.takeIf { it >= 0 },
        systemDefault.takeIf { it >= 0 },
    )
    return candidates.firstOrNull { it != null && it in activeSubscriptionIds }
}

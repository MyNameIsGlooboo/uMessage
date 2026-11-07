package com.glooboo.umessage.messaging

import android.annotation.SuppressLint
import android.content.Context
import com.glooboo.umessage.extensions.subscriptionManagerCompat

/**
 * B16: a subscription id kept with a retry, a scheduled message or a headless send may belong to a SIM that was swapped
 * or switched off since. It is used only while it is still active; otherwise null, which means the normal choice (the
 * system default SIM), instead of a failure or a send from the wrong SIM. When the active SIMs cannot be read (no
 * permission, or none listed) the stored id is kept as it was.
 */
internal fun usableSubscriptionId(stored: Int?, activeIds: List<Int>?): Int? {
    if (stored == null || stored < 0) return stored
    if (activeIds.isNullOrEmpty()) return stored
    return stored.takeIf { it in activeIds }
}

@SuppressLint("MissingPermission") // read inside runCatching: without the permission the stored id is kept
fun Context.activeSendSubscriptionId(stored: Int?): Int? {
    if (stored == null || stored < 0) return stored
    val active = runCatching { subscriptionManagerCompat().activeSubscriptionInfoList?.map { it.subscriptionId } }
        .getOrNull()
    return usableSubscriptionId(stored, active)
}

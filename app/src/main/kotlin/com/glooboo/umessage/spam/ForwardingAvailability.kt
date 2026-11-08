package com.glooboo.umessage.spam

import android.content.Context

/**
 * Whether message forwarding exists in this build. It exists in every build, Google Play included: it is optional,
 * off until the user adds a rule, runs on the device and sends plain SMS on the user's own plan. Kept as one place
 * to ask so a build could still switch it off in future.
 */
object ForwardingAvailability {
    /** True when this build has forwarding: always. */
    @Suppress("UNUSED_PARAMETER", "FunctionOnlyReturningConstant")
    fun isAvailable(context: Context): Boolean = true
}

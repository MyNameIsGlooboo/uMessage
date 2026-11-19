package com.glooboo.umessage.testing

import android.content.BroadcastReceiver
import android.os.Bundle
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBroadcastPendingResult
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Gives a receiver a real PendingResult, so its goAsync() works and a test can see whether the broadcast was finished.
 * (A bare receiver built in a test has none: goAsync() returns null.)
 */
class WatchedBroadcast private constructor(private val result: BroadcastReceiver.PendingResult) {

    /** True once the receiver called finish(), waiting up to [timeoutMs] for a background worker to get there. */
    fun awaitFinished(timeoutMs: Long = 15_000L): Boolean = try {
        Shadow.extract<ShadowBroadcastPendingResult>(result).future.get(timeoutMs, TimeUnit.MILLISECONDS)
        true
    } catch (_: TimeoutException) {
        false
    }

    companion object {
        /** Attaches a new PendingResult to [receiver] and returns the handle to watch it. */
        fun attachTo(receiver: BroadcastReceiver): WatchedBroadcast {
            val create = ShadowBroadcastPendingResult::class.java.getDeclaredMethod(
                "create",
                Int::class.javaPrimitiveType,
                String::class.java,
                Bundle::class.java,
                Boolean::class.javaPrimitiveType,
            ).apply { isAccessible = true }
            val result = create.invoke(null, 0, null, null, false) as BroadcastReceiver.PendingResult
            BroadcastReceiver::class.java
                .getDeclaredMethod("setPendingResult", BroadcastReceiver.PendingResult::class.java)
                .apply { isAccessible = true }
                .invoke(receiver, result)
            return WatchedBroadcast(result)
        }
    }
}

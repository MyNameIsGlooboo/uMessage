package com.glooboo.umessage.ui.thread

import android.util.Log
import com.glooboo.umessage.BuildConfig

private const val TAG = "ThreadItemsTiming"
private const val SLOW_BUILD_MS = 16L
private const val LONG_THREAD = 1_000
private const val NANOS_PER_MS = 1_000_000L

/**
 * I29: in debug builds only, logs how long [buildThreadItems] took when a long thread made it slower than one frame.
 * Counts only: no message text, names or numbers are logged. Release builds do nothing.
 */
internal fun logSlowThreadBuild(startNanos: Long, messageCount: Int, itemCount: Int) {
    if (!BuildConfig.DEBUG || messageCount < LONG_THREAD) return
    val millis = (System.nanoTime() - startNanos) / NANOS_PER_MS
    if (millis >= SLOW_BUILD_MS) Log.d(TAG, "buildThreadItems: $messageCount messages -> $itemCount rows in $millis ms")
}

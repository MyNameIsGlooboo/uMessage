package com.glooboo.umessage.dialogs

import android.os.SystemClock

/**
 * The phone's own running clock in milliseconds, which setting the date and time cannot move; -1 where it cannot be
 * read (plain JVM tests). The app lock waits are measured with it as well as with the wall clock.
 */
fun runningClock(): Long = runCatching { SystemClock.elapsedRealtime() }.getOrDefault(-1L)

/**
 * Time since the failure on the phone's own running clock, or -1 when either reading is missing or the phone was
 * restarted since (the running clock starts again, so the later reading is the smaller one).
 */
fun monotonicSince(failureElapsedMs: Long, elapsedNowMs: Long): Long =
    if (failureElapsedMs < 0L || elapsedNowMs < failureElapsedMs) -1L else elapsedNowMs - failureElapsedMs

package com.glooboo.umessage.spam.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PurgePolicyTest {

    @Test fun firstRunIsDue() {
        assertTrue(PurgePolicy.isDue(lastRun = 0, now = 1_000))
    }

    @Test fun notDueWithinInterval() {
        assertFalse(PurgePolicy.isDue(lastRun = 1_000, now = 1_000 + PurgePolicy.INTERVAL_MS - 1))
    }

    @Test fun dueAfterInterval() {
        assertTrue(PurgePolicy.isDue(lastRun = 1_000, now = 1_000 + PurgePolicy.INTERVAL_MS))
    }

    @Test fun dueWhenClockWentBackwards() {
        assertTrue(PurgePolicy.isDue(lastRun = 10_000, now = 5_000))
    }
}

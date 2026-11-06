package com.glooboo.umessage.spam.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingSmsPolicyTest {

    @Test
    fun freshRowIsNotDueYet() {
        assertFalse(PendingSmsPolicy.isDue(createdAt = 1_000, now = 1_000 + PendingSmsPolicy.GRACE_MS - 1))
    }

    @Test
    fun rowIsDueAfterGracePeriod() {
        assertTrue(PendingSmsPolicy.isDue(createdAt = 1_000, now = 1_000 + PendingSmsPolicy.GRACE_MS))
    }

    @Test
    fun rowExpiresAfterMaxAge() {
        assertFalse(PendingSmsPolicy.isExpired(createdAt = 0, now = PendingSmsPolicy.MAX_AGE_MS))
        assertTrue(PendingSmsPolicy.isExpired(createdAt = 0, now = PendingSmsPolicy.MAX_AGE_MS + 1))
    }

    @Test
    fun expiredRowIsAlsoDue() {
        val now = PendingSmsPolicy.MAX_AGE_MS + 10
        assertTrue(PendingSmsPolicy.isDue(createdAt = 0, now = now))
    }

    @Test
    fun rowWithoutLeaseIsFree() {
        assertFalse(PendingSmsPolicy.isLeased(leasedAt = null, now = 5_000))
    }

    @Test
    fun leaseHoldsUntilItRunsOut() {
        assertTrue(PendingSmsPolicy.isLeased(leasedAt = 1_000, now = 1_000))
        assertTrue(PendingSmsPolicy.isLeased(leasedAt = 1_000, now = 1_000 + PendingSmsPolicy.LEASE_MS - 1))
        assertFalse(PendingSmsPolicy.isLeased(leasedAt = 1_000, now = 1_000 + PendingSmsPolicy.LEASE_MS))
    }

    @Test
    fun leaseFromTheFutureIsIgnored() {
        // Clock moved backwards: a stale lease must not block replay forever.
        assertFalse(PendingSmsPolicy.isLeased(leasedAt = 10_000, now = 1_000))
    }

    @Test
    fun leaseOutlastsGracePeriod() {
        // A slow receiver (past the 30 s grace) is still protected from a concurrent replay.
        assertTrue(PendingSmsPolicy.LEASE_MS > PendingSmsPolicy.GRACE_MS)
    }
}

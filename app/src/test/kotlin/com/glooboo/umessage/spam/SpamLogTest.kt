package com.glooboo.umessage.spam

import com.glooboo.umessage.spam.data.BlockedLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SpamLogTest {

    private fun e(i: Int) = SpamLogEntry(i.toLong(), "+1555$i", 1, "BLOCK", "UNKNOWN_SENDER", "SMS")

    @Test
    fun keepsOnlyLastCapacityNewestFirst() {
        val log = SpamLog(null, capacity = 200, now = clock)
        repeat(250) { log.record(e(it)) }
        val list = log.entries()
        assertEquals(200, list.size)
        assertEquals(249L, list.first().time)
        assertEquals(50L, list.last().time)
    }

    @Test
    fun persistsAndReloadsFromFile() {
        val f = File.createTempFile("spamlog", ".txt").also { it.deleteOnExit() }
        val a = SpamLog(f, capacity = 5, now = clock)
        repeat(30) { a.record(e(it)) }
        val b = SpamLog(f, capacity = 5, now = clock)
        assertEquals((29 downTo 25).map { it.toLong() }, b.entries().map { it.time })
        assertTrue(f.readLines().size <= 10)
    }

    @Test
    fun entryRoundTripSanitisesSeparators() {
        val entry = SpamLogEntry(7, "a\tb\nc", -1, "ALLOW", "CONTACT", "MMS_PUSH")
        val back = SpamLog.decode(SpamLog.encode(entry))!!
        assertEquals("a b c", back.sender)
        assertEquals(-1, back.subscriptionId)
        assertEquals("MMS_PUSH", back.source)
    }

    @Test
    fun corruptLinesAreSkippedAndClearWorks() {
        val f = File.createTempFile("spamlog", ".txt").also { it.deleteOnExit() }
        f.writeText("garbage\n" + SpamLog.encode(e(1)) + "\n")
        val log = SpamLog(f, now = clock)
        assertEquals(1, log.entries().size)
        log.clear()
        assertTrue(log.entries().isEmpty())
        assertFalse(f.exists())
    }

    @Test
    fun bodyAndAddressLimits() {
        val long = "x".repeat(BlockedLimits.MAX_BODY_CHARS + 500)
        assertEquals(BlockedLimits.MAX_BODY_CHARS, BlockedLimits.limitBody(long).length)
        assertEquals("short", BlockedLimits.limitBody("short"))
        assertEquals(BlockedLimits.MAX_ADDRESS_CHARS, BlockedLimits.limitAddress("9".repeat(1000)).length)
    }

    @Test
    fun entriesOlderThanTheRetentionAreNotShown() {
        // SU-40: sender numbers must not stay around for ever.
        var now = 1_000L
        val log = SpamLog(null, retentionMs = 100, now = { now })
        log.record(e(950))
        now = 1_200L
        log.record(e(1_150))

        assertEquals(listOf(1_150L), log.entries().map { it.time })
    }

    @Test
    fun purgeRemovesExpiredEntriesFromTheFile() {
        val f = File.createTempFile("spamlog", ".txt").also { it.deleteOnExit() }
        var now = 1_000L
        SpamLog(f, retentionMs = 100, now = { now }).apply {
            record(e(990))
            record(e(995))
        }
        now = 2_000L

        SpamLog(f, retentionMs = 100, now = { now }).purgeExpired()

        assertFalse(f.exists() && f.readText().isNotBlank())
    }

    @Test
    fun defaultRetentionIsAWeek() {
        assertEquals(7L * 24 * 60 * 60 * 1000, SpamLog.RETENTION_MS)
    }

    @Test
    fun fixMarkRoundTripsWithAndWithoutRule() {
        val plain = SpamLog.decode(SpamLog.encode(e(3).copy(fix = SpamLog.FIX_ALLOWED)))!!
        assertEquals(SpamLog.FIX_ALLOWED, plain.fix)
        assertEquals(null, plain.ruleId)
        val ruled = e(4).copy(reason = "BLOCK_RULE", ruleId = 9, ruleLabel = "lottery", fix = SpamLog.FIX_RESTORED)
        assertEquals(ruled, SpamLog.decode(SpamLog.encode(ruled)))
        // Without a fix the line keeps the old six- and eight-column shapes, so older versions still read it.
        assertEquals(6, SpamLog.encode(e(5)).split('\t').size)
        assertEquals(8, SpamLog.encode(ruled.copy(fix = "")).split('\t').size)
    }

    @Test
    fun oldLinesStillReadAndDamagedOnesAreSkipped() {
        assertEquals("", SpamLog.decode("1\t+1555\t1\tBLOCK\tUNKNOWN_SENDER\tSMS")!!.fix)
        assertEquals(7L, SpamLog.decode("1\t+1555\t1\tBLOCK\tBLOCK_RULE\tSMS\t7\tlottery")!!.ruleId)
        // An eight-column line must name its rule; a nine-column one may leave it empty.
        assertEquals(null, SpamLog.decode("1\t+1555\t1\tBLOCK\tBLOCK_RULE\tSMS\t\tlottery"))
        assertEquals(null, SpamLog.decode("1\t+1555\t1\tBLOCK\tBLOCK_RULE\tSMS\tx\t\tALLOWED"))
        assertEquals(null, SpamLog.decode("1\t+1555\t1\tBLOCK\tBLOCK_RULE\tSMS\t7"))
    }

    @Test
    fun fixMarkIsStoredInTheFileAndCanBeUndone() {
        val f = File.createTempFile("spamlog", ".txt").also { it.deleteOnExit() }
        val a = SpamLog(f, capacity = 10, now = clock)
        repeat(3) { a.record(e(it)) }
        assertTrue(a.markFixed(1, "+15551", SpamLog.FIX_BLOCKED))
        assertFalse(a.markFixed(1, "+15559", SpamLog.FIX_BLOCKED))

        val b = SpamLog(f, capacity = 10, now = clock)
        assertEquals(listOf("", SpamLog.FIX_BLOCKED, ""), b.entries().map { it.fix })
        assertTrue(b.markFixed(1, "+15551", ""))
        assertEquals(listOf("", "", ""), SpamLog(f, capacity = 10, now = clock).entries().map { it.fix })
    }

    private val clock = { 1_000L }
}

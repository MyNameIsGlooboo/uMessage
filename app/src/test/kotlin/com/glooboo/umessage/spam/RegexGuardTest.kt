package com.glooboo.umessage.spam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RegexGuardTest {

    @Test fun acceptsOrdinaryPatterns() {
        assertNull(RegexGuard.validate("win\\s+\\$\\d+"))
        assertNull(RegexGuard.validate("(free|cheap) (gift|prize)"))
        assertNull(RegexGuard.validate("[a-z]+@[a-z]+\\.com"))
        assertNull(RegexGuard.validate("(ab)+c"))
        assertNull(RegexGuard.validate("(a+)?b"))
        assertNull(RegexGuard.validate("\\(\\d+\\)+x"))
    }

    @Test fun rejectsBadSyntaxEmptyAndTooLong() {
        assertEquals(RegexProblem.SYNTAX, RegexGuard.validate("([unclosed"))
        assertEquals(RegexProblem.EMPTY, RegexGuard.validate("   "))
        assertEquals(RegexProblem.TOO_LONG, RegexGuard.validate("a".repeat(RegexGuard.MAX_PATTERN_CHARS + 1)))
    }

    @Test fun rejectsPatternsMatchingEverything() {
        assertEquals(RegexProblem.MATCHES_EMPTY, RegexGuard.validate("x*"))
        assertEquals(RegexProblem.MATCHES_EMPTY, RegexGuard.validate("^"))
    }

    @Test fun rejectsNestedRepeats() {
        assertEquals(RegexProblem.NESTED_REPEAT, RegexGuard.validate("(a+)+$"))
        assertEquals(RegexProblem.NESTED_REPEAT, RegexGuard.validate("(\\w+\\s?)*end"))
        assertEquals(RegexProblem.NESTED_REPEAT, RegexGuard.validate("((ab*))+c"))
        assertEquals(RegexProblem.NESTED_REPEAT, RegexGuard.validate("(x[a-z]{2,})+y"))
        assertEquals(RegexProblem.NESTED_REPEAT, RegexGuard.validate("(?:a|b+){3,}z"))
    }

    @Test fun characterClassesAndEscapesAreNotGroups() {
        assertFalse(RegexGuard.hasNestedRepeat("[(a+)]+"))
        assertFalse(RegexGuard.hasNestedRepeat("\\(a+\\)+"))
        assertFalse(RegexGuard.hasNestedRepeat("\\Q(a+)+\\E"))
        assertTrue(RegexGuard.hasNestedRepeat("([)]+)+"))
    }

    @Test fun findIsCaseInsensitiveAndCapped() {
        val pattern = RegexGuard.compile("lottery")
        assertNotNull(pattern)
        assertTrue(RegexGuard.find(pattern!!, "You won the LOTTERY"))
        assertFalse(RegexGuard.find(pattern, "x".repeat(RegexGuard.MAX_BODY_CHARS) + "lottery"))
    }

    @Test fun slowPatternTimesOutInsteadOfHanging() {
        // Passes validate() (no nested group repeat) yet takes O(n^4) steps on a long body without an x.
        val pattern = RegexGuard.compile(".*.*.*.*x")
        assertNotNull(pattern)
        val start = System.nanoTime()
        val outcome = RegexGuard.search(pattern!!, "a".repeat(RegexGuard.MAX_BODY_CHARS))
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertEquals(RegexGuard.Outcome.TIMED_OUT, outcome)
        assertTrue("took $elapsedMs ms", elapsedMs < 2_000)
        assertFalse(RegexGuard.find(pattern, "a".repeat(RegexGuard.MAX_BODY_CHARS)))
        assertEquals(RegexGuard.Outcome.MATCH, RegexGuard.search(pattern, "aax"))
    }

    @Test fun stuckMatchTimesOutOnTheWallClock() {
        // The task never reads the input, as on Android: only a real wall-clock wait can end the call.
        val release = CountDownLatch(1)
        try {
            val start = System.nanoTime()
            val outcome = RegexGuard.runWithDeadline(50) {
                release.await(10, TimeUnit.SECONDS)
                RegexGuard.Outcome.MATCH
            }
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            assertEquals(RegexGuard.Outcome.TIMED_OUT, outcome)
            assertTrue("took $elapsedMs ms", elapsedMs < 2_000)
        } finally {
            release.countDown()
        }
    }

    @Test fun finishedMatchReturnsItsOutcome() {
        assertEquals(RegexGuard.Outcome.MATCH, RegexGuard.runWithDeadline(2_000) { RegexGuard.Outcome.MATCH })
        assertEquals(RegexGuard.Outcome.NO_MATCH, RegexGuard.runWithDeadline(2_000) { RegexGuard.Outcome.NO_MATCH })
        assertEquals(RegexGuard.Outcome.NO_MATCH, RegexGuard.runWithDeadline(2_000) { error("boom") })
    }

    @Test fun stuckMatchesCannotExhaustThreads() {
        val release = CountDownLatch(1)
        try {
            // More stuck matches than the pool has threads: the extra ones are refused, not queued or started.
            val outcomes = List(20) {
                RegexGuard.runWithDeadline(20) {
                    release.await(10, TimeUnit.SECONDS)
                    RegexGuard.Outcome.MATCH
                }
            }
            assertTrue(outcomes.contains(RegexGuard.Outcome.TIMED_OUT))
            assertTrue(outcomes.contains(RegexGuard.Outcome.NO_MATCH))
        } finally {
            release.countDown()
        }
        // Once the stuck matches end, the pool serves matches again.
        val deadline = System.nanoTime() + 5_000_000_000L
        var outcome = RegexGuard.runWithDeadline(2_000) { RegexGuard.Outcome.MATCH }
        while (outcome != RegexGuard.Outcome.MATCH && System.nanoTime() < deadline) {
            Thread.sleep(10)
            outcome = RegexGuard.runWithDeadline(2_000) { RegexGuard.Outcome.MATCH }
        }
        assertEquals(RegexGuard.Outcome.MATCH, outcome)
    }

    @Test fun compileReturnsNullForRejectedPatterns() {
        assertNull(RegexGuard.compile("(a+)+"))
        assertNull(RegexGuard.compile("("))
    }
}

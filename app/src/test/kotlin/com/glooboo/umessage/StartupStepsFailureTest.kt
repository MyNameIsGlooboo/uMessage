package com.glooboo.umessage

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * J13: a start-up step that throws an exception is logged, recorded in [App.failedSteps] and skipped; the steps after
 * it still run, in their order, so one failure after an update cannot hold back a migration or a clean-up until the
 * next start. An Error is not caught.
 */
@RunWith(AndroidJUnit4::class)
class StartupStepsFailureTest {

    @Before
    fun clearFailures() = App.failedSteps.clear()

    @Test
    fun aStepThatThrowsDoesNotStopTheNextSteps() {
        val ran = mutableListOf<String>()
        val steps = listOf<Pair<String, () -> Unit>>(
            "first" to { ran += "first" },
            "broken" to { throw IllegalStateException("store unavailable") },
            "migration" to { ran += "migration" },
            "also broken" to { throw SecurityException("no permission") },
            "clean-up" to { ran += "clean-up" },
        )

        App.runStartupSteps(steps)

        assertThat(ran).containsExactly("first", "migration", "clean-up").inOrder()
        assertThat(App.failedSteps).containsExactly("broken", "also broken").inOrder()
    }

    @Test
    fun everyStepRunsOnceWhenNoneThrows() {
        val ran = mutableListOf<String>()
        val steps = (1..4).map { n -> "step $n" to { ran += "step $n"; Unit } }

        App.runStartupSteps(steps)

        assertThat(ran).containsExactly("step 1", "step 2", "step 3", "step 4").inOrder()
        assertThat(App.failedSteps).isEmpty()
    }

    @Test
    fun anErrorIsNotCaught() {
        val steps = listOf<Pair<String, () -> Unit>>("out of memory" to { throw OutOfMemoryError("test") })

        assertThrows(OutOfMemoryError::class.java) { App.runStartupSteps(steps) }
        assertThat(App.failedSteps).isEmpty()
    }
}

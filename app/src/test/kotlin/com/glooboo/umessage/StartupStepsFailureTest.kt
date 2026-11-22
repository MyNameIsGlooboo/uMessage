package com.glooboo.umessage

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/**
 * J13: a start-up step that throws is logged and skipped; the steps after it still run, in their order, so one
 * failure after an update cannot hold back a migration or a clean-up until the next start.
 */
@RunWith(AndroidJUnit4::class)
class StartupStepsFailureTest {

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
    }

    @Test
    fun everyStepRunsOnceWhenNoneThrows() {
        val ran = mutableListOf<String>()
        val steps = (1..4).map { n -> "step $n" to { ran += "step $n"; Unit } }

        App.runStartupSteps(steps)

        assertThat(ran).containsExactly("step 1", "step 2", "step 3", "step 4").inOrder()
    }
}

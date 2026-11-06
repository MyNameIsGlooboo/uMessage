package com.glooboo.umessage.ui.thread

import org.junit.Assert.assertEquals
import org.junit.Test

/** TU-03: a group send with "ask for SIM" on asks for the SIM once, then the group question, then sends. */
class SendStepsTest {

    private val asked = ArrayList<String>()
    private var groupAnswered = false

    private fun steps(askSim: Boolean, group: Boolean, answer: Boolean = true) = SendSteps(
        needsSim = { askSim },
        askSim = { onChosen -> asked += "sim"; if (answer) onChosen() },
        // Answering the group question saves the choice, so it is not needed again.
        needsGroupChoice = { group && !groupAnswered },
        askGroupChoice = { onChosen -> asked += "group"; if (answer) { groupAnswered = true; onChosen() } },
        send = { asked += "send" },
    )

    @Test
    fun groupSendAsksForTheSimOnlyOnce() {
        steps(askSim = true, group = true).run()
        assertEquals(listOf("sim", "group", "send"), asked)
    }

    @Test
    fun noQuestionsSendsAtOnce() {
        steps(askSim = false, group = false).run()
        assertEquals(listOf("send"), asked)
    }

    @Test
    fun cancellingTheSimQuestionSendsNothing() {
        steps(askSim = true, group = true, answer = false).run()
        assertEquals(listOf("sim"), asked)
    }

    @Test
    fun secondGroupSendSkipsTheAnsweredQuestion() {
        steps(askSim = true, group = true).run()
        asked.clear()
        steps(askSim = true, group = true).run()
        assertEquals(listOf("sim", "send"), asked)
    }
}

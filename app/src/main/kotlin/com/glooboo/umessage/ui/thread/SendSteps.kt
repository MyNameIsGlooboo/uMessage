package com.glooboo.umessage.ui.thread

/**
 * The questions asked after a tap on Send, in order, each at most once: which SIM (when the app is set to ask), then
 * whether a group message goes as one MMS (until the user has answered that once). TU-03: the group answer used to
 * start Send over from the top, so the SIM was asked for a second time.
 *
 * Each ask function shows its question and calls the given function once the user has answered; when the user cancels
 * it simply never calls it.
 */
internal class SendSteps(
    private val needsSim: () -> Boolean,
    private val askSim: (onChosen: () -> Unit) -> Unit,
    private val needsGroupChoice: () -> Boolean,
    private val askGroupChoice: (onChosen: () -> Unit) -> Unit,
    private val send: () -> Unit,
) {
    fun run() {
        if (needsSim()) askSim(::afterSim) else afterSim()
    }

    private fun afterSim() {
        if (needsGroupChoice()) askGroupChoice(send) else send()
    }
}

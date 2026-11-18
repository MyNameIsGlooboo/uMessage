package com.glooboo.umessage.ui.main

/** What the first-day checklist asks for, in order. [Contacts], [Battery] and [Forwarding] are optional. */
enum class ChecklistStep(val required: Boolean) {
    DefaultSms(true),
    Notifications(true),
    Contacts(false),
    BlockedTab(true),
    Battery(false),
    Forwarding(false),
}

/** True once every required step is done; an optional step does not hold the card back. */
fun checklistComplete(done: Set<ChecklistStep>): Boolean =
    ChecklistStep.entries.filter { it.required }.all { it in done }

/**
 * The card can be closed for good only once every required step is done; until then it stays in the inbox. A step in
 * [impossible] cannot be done on this phone (no SMS role to give), so it does not hold the card either.
 */
fun checklistCanClose(done: Set<ChecklistStep>, impossible: Set<ChecklistStep> = emptySet()): Boolean =
    ChecklistStep.entries.filter { it.required && it !in impossible }.all { it in done }

/** The checklist card's title: still to do, only the optional step left, or everything done. */
enum class ChecklistTitle { Steps, BasicsDone, AllDone }

/** N7: "You are all set" only when every step, the optional one too, is done; the counter would say otherwise. */
fun checklistTitle(done: Set<ChecklistStep>): ChecklistTitle = when {
    ChecklistStep.entries.all { it in done } -> ChecklistTitle.AllDone
    checklistComplete(done) -> ChecklistTitle.BasicsDone
    else -> ChecklistTitle.Steps
}

/** Done steps over all steps, for the "2 of 4" line and the progress bar. */
fun checklistProgress(done: Set<ChecklistStep>): Pair<Int, Int> = done.size to ChecklistStep.values().size

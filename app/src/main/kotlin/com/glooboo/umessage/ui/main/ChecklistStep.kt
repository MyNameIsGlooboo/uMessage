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

/** After this long the card can be closed even with a required step open (notifications kept off on purpose). */
const val CHECKLIST_FORCE_CLOSE_MS = 7L * 24 * 60 * 60 * 1000

/**
 * The card can be closed for good only once every required step is done, or once it has been shown for
 * [CHECKLIST_FORCE_CLOSE_MS] ([shownForMs], from the first-shown time); until then it stays in the inbox. A step in
 * [impossible] cannot be done on this phone (no SMS role to give), so it does not hold the card either.
 */
fun checklistCanClose(
    done: Set<ChecklistStep>,
    impossible: Set<ChecklistStep> = emptySet(),
    shownForMs: Long = 0L,
): Boolean = shownForMs >= CHECKLIST_FORCE_CLOSE_MS ||
    ChecklistStep.entries.filter { it.required && it !in impossible }.all { it in done }

/** The checklist card's title: still to do, only the optional step left, everything done, or a count. */
enum class ChecklistTitle { Steps, BasicsDone, AllDone, Count }

/**
 * N7: "You are all set" only when every step that can be done, the optional one too, is done; the counter would say
 * otherwise. "The basics are done" only when every required step is done; a required step that cannot be done on this
 * phone ([impossible]) leaves the plain count ([ChecklistTitle.Count]) instead.
 */
fun checklistTitle(done: Set<ChecklistStep>, impossible: Set<ChecklistStep> = emptySet()): ChecklistTitle = when {
    !checklistCanClose(done, impossible) -> ChecklistTitle.Steps
    ChecklistStep.entries.any { it.required && it in impossible } -> ChecklistTitle.Count
    ChecklistStep.entries.all { it in done } -> ChecklistTitle.AllDone
    else -> ChecklistTitle.BasicsDone
}

/** Done steps over all steps, for the "2 of 4" line and the progress bar. */
fun checklistProgress(done: Set<ChecklistStep>): Pair<Int, Int> = done.size to ChecklistStep.values().size

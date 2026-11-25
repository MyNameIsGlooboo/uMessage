package com.glooboo.umessage.ui.setup

import com.glooboo.umessage.helpers.BackgroundStatus
import com.glooboo.umessage.helpers.OemStepKind
import com.glooboo.umessage.helpers.StepCheck
import com.glooboo.umessage.helpers.StepState

/**
 * What the Setup screen says about one step. Only [DONE] is verified by Android; [MARKED_BY_YOU] is the user's word
 * for a step Android cannot report, and [CANNOT_CHECK] is such a step they have not marked.
 */
enum class SetupMark { DONE, NOT_DONE, CANNOT_CHECK, MARKED_BY_YOU }

/** A step Android can report (a permission, the default SMS role, notifications): done or not done. */
fun checkableMark(done: Boolean): SetupMark = if (done) SetupMark.DONE else SetupMark.NOT_DONE

/**
 * A "Keep uMessage running" step. A step Android cannot read is [SetupMark.CANNOT_CHECK] until the user marks it, but
 * [SetupMark.NOT_DONE] while Android reports a background limit for a step that should lift it, or once the user
 * marked it and the limit is still there ([markedByUser] with a [StepState.NOT_DONE] state).
 */
fun oemStepMark(kind: OemStepKind, state: StepState, status: BackgroundStatus, markedByUser: Boolean): SetupMark =
    when (state) {
        StepState.DONE_VERIFIED -> SetupMark.DONE
        StepState.DONE_BY_USER -> SetupMark.MARKED_BY_YOU
        StepState.NOT_DONE -> {
            val limited = status.backgroundRestricted || status.restrictedBucket
            val knownNotDone = kind.check == StepCheck.VERIFIED || markedByUser || (kind.backgroundLimit && limited)
            if (knownNotDone) SetupMark.NOT_DONE else SetupMark.CANNOT_CHECK
        }
    }

/** One row of the Setup screen, reduced to what its summary needs. */
data class SetupRowState(val mark: SetupMark, val required: Boolean)

/** True when every required row is verified done. An optional row never holds the summary back. */
fun setupRequiredDone(rows: List<SetupRowState>): Boolean =
    rows.all { !it.required || it.mark == SetupMark.DONE }

/** What the Setup screen's headline says. */
enum class SetupHeadline { ALL_SET, OPTIONAL_LEFT, SOME_OPEN }

/** Required rows decide; when they are all done, an open optional step ([optionalOpen]) is still mentioned. */
fun setupHeadline(rows: List<SetupRowState>, optionalOpen: Boolean): SetupHeadline = when {
    !setupRequiredDone(rows) -> SetupHeadline.SOME_OPEN
    optionalOpen -> SetupHeadline.OPTIONAL_LEFT
    else -> SetupHeadline.ALL_SET
}

/**
 * B2: true when the Setup page's headline asks the user to do something (a required row is open, e.g. the default SMS
 * role or a permission, or Android reports a background limit). The Setup row on the main list shows its dot then.
 */
fun setupNeedsAttention(rows: List<SetupRowState>, batteryOpen: Boolean): Boolean =
    setupHeadline(rows, batteryOpen) != SetupHeadline.ALL_SET

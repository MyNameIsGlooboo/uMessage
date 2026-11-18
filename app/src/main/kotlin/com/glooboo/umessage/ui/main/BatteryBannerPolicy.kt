package com.glooboo.umessage.ui.main

import com.glooboo.umessage.helpers.BackgroundStatus
import com.glooboo.umessage.helpers.Oem
import com.glooboo.umessage.helpers.OemHelper
import com.glooboo.umessage.helpers.OemStep
import com.glooboo.umessage.helpers.OemStepKind
import com.glooboo.umessage.helpers.StepState

/** When the "keep uMessage running" banner on the Messages tab shows, and how long "Not now" hides it. */
object BatteryBannerPolicy {

    /** The same four weeks as the notifications banner. */
    const val DISMISS_MS = 28L * 24 * 60 * 60 * 1000

    /**
     * Shown on a phone from a vendor that kills background apps, and on any phone where Android says uMessage is
     * restricted in the background. In both cases only while a step of the guide (the App info page excluded, it has
     * no state of its own) is not done. A plain phone that is not restricted never sees it.
     */
    fun needed(
        oem: Oem,
        steps: List<OemStep>,
        status: BackgroundStatus,
        isMarked: (OemStepKind) -> Boolean,
    ): Boolean {
        val limited = status.backgroundRestricted || status.restrictedBucket
        if (oem == Oem.OTHER && !limited) return false
        return steps.filter { it.kind != OemStepKind.APP_INFO }
            .any { OemHelper.stateOf(it.kind, status, isMarked(it.kind)) == StepState.NOT_DONE }
    }

    /** True while a dismissal made at [dismissedAt] still hides the banner; 0 means never dismissed. */
    fun dismissed(now: Long, dismissedAt: Long): Boolean = dismissedAt > 0L && now - dismissedAt in 0 until DISMISS_MS
}

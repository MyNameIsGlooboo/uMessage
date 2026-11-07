package com.glooboo.umessage.ui.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable

/**
 * F8: the page list of the flow. Whether the Allow step is part of it is decided once, when the flow first opens
 * ([allowNeededNow]), and saved with the instance state next to the pager's page position. Without that, a rotation
 * or process restart after the user granted everything rebuilt the list without the Allow step while the saved
 * position stayed the same, so the position pointed one page too far and the unknown-senders step was skipped.
 */
@Composable
internal fun rememberOnboardingPages(allowNeededNow: Boolean): List<OnboardingPage> {
    val allowNeeded = rememberSaveable { allowNeededNow }
    return remember(allowNeeded) { OnboardingState.pagesFor(allowNeeded) }
}

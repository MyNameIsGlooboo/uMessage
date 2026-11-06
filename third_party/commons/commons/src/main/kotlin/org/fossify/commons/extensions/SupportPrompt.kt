package org.fossify.commons.extensions

import android.app.Activity
import org.fossify.commons.R
import org.fossify.commons.dialogs.DonateDialog
import org.fossify.commons.dialogs.RateAppDialog

private const val DAY_MILLIS = 24L * 60L * 60L * 1000L
private const val RATING_DELAY_MILLIS = 5L * DAY_MILLIS
private const val DONATION_DELAY_MILLIS = 10L * DAY_MILLIS

internal fun Activity.showAutomaticSupportPromptIfEligible() {
    // uMessage: no rating or donation prompts.
}

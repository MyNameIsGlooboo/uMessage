package com.glooboo.umessage.extensions

import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.Temporal
import java.util.Locale

/** Date in the device locale's medium style ("Oct 30, 2026", "30 Oct 2026"), as the system shows dates. */
fun Temporal.formatLocalizedDate(): String {
    return DateTimeFormatter
        .ofLocalizedDate(FormatStyle.MEDIUM)
        .withLocale(Locale.getDefault())
        .format(this)
}

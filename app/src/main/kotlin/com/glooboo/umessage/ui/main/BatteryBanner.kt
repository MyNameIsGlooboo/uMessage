package com.glooboo.umessage.ui.main

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.glooboo.umessage.R
import com.glooboo.umessage.helpers.OemHelper
import com.glooboo.umessage.ui.icons.Symbols
import com.glooboo.umessage.ui.onboarding.OnboardingState
import com.glooboo.umessage.ui.setup.setupScreenIntent

/**
 * A banner on the Messages tab while Android may stop uMessage in the background (see [BatteryBannerPolicy]).
 * Re-checked on every resume, so it goes away once the guide's steps are done. "Not now" hides it for four weeks.
 */
@Composable
fun BatteryBanner(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    var dismissed by remember { mutableStateOf(false) }
    if (dismissed) return
    val needed = remember(refresh) { batteryGuideNeeded(context) }
    if (!needed || BatteryBannerPolicy.dismissed(System.currentTimeMillis(), dismissedAt(context))) return

    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        ) {
            Icon(Symbols.Smartphone, contentDescription = null, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.setup_battery_banner_title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.setup_battery_banner_body), style = MaterialTheme.typography.bodyMedium)
            }
            Column(horizontalAlignment = Alignment.End) {
                FilledTonalButton(onClick = { runCatching { context.startActivity(context.setupScreenIntent()) } }) {
                    Text(stringResource(R.string.setup_battery_banner_action))
                }
                TextButton(onClick = {
                    dismiss(context)
                    dismissed = true
                }) {
                    Text(stringResource(R.string.setup_banner_dismiss))
                }
            }
        }
    }
}

/** [BatteryBannerPolicy.needed] for this phone; the first-day checklist lists its battery step on the same test. */
internal fun batteryGuideNeeded(context: Context): Boolean = runCatching {
    val oem = OemHelper.detect()
    BatteryBannerPolicy.needed(
        oem = oem,
        steps = OemHelper.steps(context, oem),
        status = OemHelper.backgroundStatus(context),
        isMarked = { OnboardingState.isOemStepMarked(context, it.key) },
    )
}.getOrDefault(false)

private const val BANNER_PREFS = "spamless_battery_banner"
private const val KEY_DISMISSED_AT = "dismissed_at"

private fun dismissedAt(context: Context): Long =
    context.getSharedPreferences(BANNER_PREFS, Context.MODE_PRIVATE).getLong(KEY_DISMISSED_AT, 0L)

private fun dismiss(context: Context) {
    context.getSharedPreferences(BANNER_PREFS, Context.MODE_PRIVATE)
        .edit { putLong(KEY_DISMISSED_AT, System.currentTimeMillis()) }
}

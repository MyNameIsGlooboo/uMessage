package com.glooboo.umessage.ui.spam

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.glooboo.umessage.R
import com.glooboo.umessage.spam.UMessage
import com.glooboo.umessage.spam.UnknownSenderMode
import com.glooboo.umessage.ui.icons.Symbols

/**
 * F4: without the Contacts permission the filter cannot tell a friend from a stranger, so it lets every number
 * through (blocking all of them would hide family and friends). The filter is only paused, though, not off: this says
 * so, with a button to allow Contacts. Nothing to say when the filter is Off anyway or the permission is there.
 */
object ContactsOffPolicy {
    fun needed(contactsGranted: Boolean, mode: UnknownSenderMode?): Boolean =
        !contactsGranted && mode != null && mode != UnknownSenderMode.OFF
}

private fun Context.contactsGranted(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

/** The banner for a screen that does not hold the filter mode itself (the Messages tab). Follows the mode live. */
@Composable
fun ContactsOffBanner(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val mode by produceState<UnknownSenderMode?>(initialValue = null, context) {
        runCatching { UMessage.repository(context).settings.collect { value = it.mode } }
    }
    ContactsOffBanner(mode, modifier)
}

/** The banner for the screen that knows the filter mode already (the Blocked tab). Re-checked on every resume. */
@Composable
fun ContactsOffBanner(mode: UnknownSenderMode?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(context.contactsGranted()) }
    // After one refusal Android may not show its dialog again; the next tap then opens the app's settings.
    var refused by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { result ->
        granted = result || context.contactsGranted()
        if (!granted) refused = true
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = context.contactsGranted()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    if (!ContactsOffPolicy.needed(granted, mode)) return

    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.large,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        ) {
            Icon(Symbols.Shield, contentDescription = null, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }) {
                Text(stringResource(R.string.s042_contacts_off_title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.s042_contacts_off_body), style = MaterialTheme.typography.bodyMedium)
            }
            FilledTonalButton(
                onClick = {
                    if (refused) openAppSettings(context) else launcher.launch(Manifest.permission.READ_CONTACTS)
                },
            ) { Text(stringResource(R.string.s042_contacts_off_allow)) }
        }
    }
}

private fun openAppSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

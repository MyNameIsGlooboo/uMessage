package com.glooboo.umessage.ui.spam

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.glooboo.umessage.R

/** Asks for the Contacts permission when "Choose from contacts" is tapped; typing a number never needs it. */
class ContactsAccess internal constructor(private val ask: (onGranted: () -> Unit) -> Unit) {
    /** Runs [onGranted] now when Contacts access is on, otherwise asks first and runs it after a yes. */
    fun withAccess(onGranted: () -> Unit) = ask(onGranted)
}

private fun Context.contactsAllowed(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

@Composable
fun rememberContactsAccess(): ContactsAccess {
    val context = LocalContext.current
    // After one refusal Android may not show its dialog again; the next tap then opens the app's settings.
    var refused by rememberSaveable { mutableStateOf(false) }
    val pending = remember { arrayOfNulls<() -> Unit>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { result ->
        val action = pending[0]
        pending[0] = null
        if (result || context.contactsAllowed()) {
            action?.invoke()
        } else {
            refused = true
            Toast.makeText(context, R.string.p44_contacts_denied, Toast.LENGTH_LONG).show()
        }
    }
    return remember(context, launcher) {
        ContactsAccess { onGranted ->
            when {
                context.contactsAllowed() -> onGranted()
                refused -> openAppSettings(context)
                else -> {
                    pending[0] = onGranted
                    launcher.launch(Manifest.permission.READ_CONTACTS)
                }
            }
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

package com.glooboo.umessage.ui.thread

import android.content.Intent
import android.provider.Settings
import androidx.activity.ComponentActivity
import com.glooboo.umessage.R
import com.glooboo.umessage.helpers.LocationResult
import com.glooboo.umessage.helpers.LocationSharer
import com.glooboo.umessage.ui.components.showConfirmDialog
import com.glooboo.umessage.ui.components.showPermissionRequiredDialog
import org.fossify.commons.extensions.toast

/**
 * The "Location" entry of the attach sheet. The position is asked only after this tap (the permission after an
 * explanation), once, and only as a link put in the message field: the user reads it and presses Send.
 * [requestPermission] opens the system prompt; [insert] puts the link text in the field.
 */
class ThreadLocationController(
    private val activity: ComponentActivity,
    private val requestPermission: () -> Unit,
    private val insert: (String) -> Unit,
    private val sharer: LocationSharer = LocationSharer(activity),
) {
    fun start() {
        if (sharer.hasPermission()) {
            fetch()
        } else {
            showPermissionRequiredDialog(
                activity = activity,
                text = activity.getString(R.string.i52_permission_explanation),
                onGrant = requestPermission,
            )
        }
    }

    /** The system prompt was answered; the user may have chosen the approximate position only. */
    fun onPermissionResult() {
        if (sharer.hasPermission()) fetch() else activity.toast(R.string.i52_denied)
    }

    private fun fetch() {
        activity.toast(R.string.i52_finding)
        sharer.requestLink { result ->
            when (result) {
                is LocationResult.Found -> insert(result.text)
                LocationResult.NoPermission -> activity.toast(R.string.i52_denied)
                LocationResult.NoFix -> activity.toast(R.string.i52_no_fix)
                LocationResult.LocationOff -> askToTurnOn()
            }
        }
    }

    private fun askToTurnOn() {
        activity.showConfirmDialog(
            message = activity.getString(R.string.i52_off_message),
            confirmLabel = activity.getString(R.string.i52_open_settings),
            dismissLabel = activity.getString(R.string.screens_cancel),
        ) {
            runCatching { activity.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
        }
    }
}

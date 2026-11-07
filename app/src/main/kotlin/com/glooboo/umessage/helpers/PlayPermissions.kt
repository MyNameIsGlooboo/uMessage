package com.glooboo.umessage.helpers

import android.app.AlarmManager
import android.content.Context
import android.os.Build
import androidx.core.app.AlarmManagerCompat
import com.glooboo.umessage.R

/**
 * What the build declares in its manifest. The Google Play build does not declare the photo-library permissions
 * (READ_MEDIA_IMAGES, READ_MEDIA_VISUAL_USER_SELECTED) or SCHEDULE_EXACT_ALARM: the system photo picker still adds
 * pictures, and scheduled messages and reminders use inexact alarms. The foss build declares them. The flavor decides
 * through the `is_google_play_build` bool.
 */
object PlayPermissions {
    /** The recent photos row asks for the photo library only in a build that declares that permission. */
    fun recentPhotosAvailableFor(googlePlayBuild: Boolean): Boolean = !googlePlayBuild

    /** Exact alarms are used, and offered in settings, only in a build that declares SCHEDULE_EXACT_ALARM. */
    fun exactAlarmsDeclaredFor(googlePlayBuild: Boolean): Boolean = !googlePlayBuild

    /** The exact alarm decision: declared by this build, and allowed by the system (always true before Android 12). */
    fun useExactAlarmFor(googlePlayBuild: Boolean, systemAllows: Boolean): Boolean =
        exactAlarmsDeclaredFor(googlePlayBuild) && systemAllows
}

private fun Context.isGooglePlayBuild(): Boolean = applicationContext.resources.getBoolean(R.bool.is_google_play_build)

/** True when this build shows the recent photos row (and so may ask for the photo library). */
fun Context.recentPhotosAvailable(): Boolean = PlayPermissions.recentPhotosAvailableFor(isGooglePlayBuild())

/** True when this build declares the exact alarm permission, so the "Alarms & reminders" setting applies to it. */
fun Context.exactAlarmsDeclared(): Boolean = PlayPermissions.exactAlarmsDeclaredFor(isGooglePlayBuild())

/**
 * True when an exact alarm may be set now. False in the Google Play build, which does not declare the permission (on
 * Android 12+ the system also answers false there), so callers take the inexact path without prompting.
 */
fun Context.canUseExactAlarms(alarmManager: AlarmManager): Boolean = PlayPermissions.useExactAlarmFor(
    isGooglePlayBuild(),
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S || AlarmManagerCompat.canScheduleExactAlarms(alarmManager),
)

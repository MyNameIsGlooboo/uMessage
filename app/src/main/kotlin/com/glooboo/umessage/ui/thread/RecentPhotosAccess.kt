package com.glooboo.umessage.ui.thread

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** How much of the phone's pictures uMessage may read: all, only the ones the user chose (Android 14), or none. */
enum class PhotoAccess { None, Partial, Full }

/** The media permission states behind the recent photos row of the attach sheet. */
object RecentPhotosAccess {
    private const val API_PARTIAL_ACCESS = 34
    private const val API_MEDIA_PERMISSIONS = 33

    fun resolve(sdk: Int, granted: (String) -> Boolean): PhotoAccess = when {
        sdk >= API_MEDIA_PERMISSIONS && granted(Manifest.permission.READ_MEDIA_IMAGES) -> PhotoAccess.Full
        sdk >= API_PARTIAL_ACCESS && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> PhotoAccess.Partial
        sdk < API_MEDIA_PERMISSIONS && granted(Manifest.permission.READ_EXTERNAL_STORAGE) -> PhotoAccess.Full
        else -> PhotoAccess.None
    }

    /** What the system prompt asks for. Android 14 offers "Select photos" when both media permissions are requested. */
    fun permissions(sdk: Int): Array<String> = when {
        sdk >= API_PARTIAL_ACCESS ->
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        sdk >= API_MEDIA_PERMISSIONS -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun current(context: Context): PhotoAccess = resolve(Build.VERSION.SDK_INT) {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
}

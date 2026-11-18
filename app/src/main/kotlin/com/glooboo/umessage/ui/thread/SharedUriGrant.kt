package com.glooboo.umessage.ui.thread

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process

/** True when this app holds a read right on [uri] itself (the check behind [readableByApp]). */
fun Context.canReadUri(uri: Uri): Boolean =
    checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION) ==
        PackageManager.PERMISSION_GRANTED

/**
 * The uris of [uris] this app may itself pass on for reading. Granting a uri the app holds no read right on makes
 * `startActivity` throw a SecurityException, so those are left out.
 */
fun Context.readableByApp(uris: List<Uri>, isReadable: (Uri) -> Boolean = ::canReadUri): List<Uri> =
    uris.filter(isReadable)

/**
 * Hands the read right on shared files to the chat that is started: the picker closes right after it opens the chat,
 * and without the grant in the intent the right ends with it, before a video or PDF is read at send time.
 */
fun Intent.grantReadOf(uris: List<Uri>) {
    val first = uris.firstOrNull() ?: return
    val clip = ClipData.newRawUri(null, first)
    uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
    clipData = clip
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}

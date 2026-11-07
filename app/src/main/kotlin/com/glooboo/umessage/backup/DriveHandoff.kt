package com.glooboo.umessage.backup

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import java.io.File

/**
 * "Save to Google Drive" without any network code in uMessage. The backup is written to one file in the app's cache
 * and that file is handed to the Google Drive app with a plain ACTION_SEND restricted to its package, so Drive's own
 * "Save to Drive" screen (account, folder) opens. The Drive app does the upload; uMessage never connects to the
 * internet. When the Drive app is not installed the caller falls back to the system file picker.
 */
object DriveHandoff {
    const val PACKAGE = "com.google.android.apps.docs"

    /** The storage provider of the Drive app, where the system file picker can open "My Drive". */
    const val STORAGE_AUTHORITY = "com.google.android.apps.docs.storage"

    /** The cache folder of the files waiting to be handed over; the daily cache clean-up empties it. */
    const val DIR = "drive_backups"

    private const val ROOT_DOCUMENT_ID = "root"

    /** The folder of the hand-over files (created when missing). Old copies are removed so only one remains. */
    fun newFile(context: Context, name: String): File {
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        return File(dir, name)
    }

    /** Makes the content URI of a file; only tests replace it (FileProvider cannot resolve folders in Robolectric). */
    internal val defaultUriFactory: (Context, File) -> Uri = { context, file ->
        FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
    }
    internal var uriFactory: (Context, File) -> Uri = defaultUriFactory

    /** The file URI Drive can read: served by the app's FileProvider, which covers the [DIR] cache folder. */
    fun uriFor(context: Context, file: File): Uri = uriFactory(context, file)

    /** The hand-over: send [file] (of type [mimeType]) to the Drive app only, with a read grant for its content URI. */
    fun sendIntent(context: Context, file: File, mimeType: String): Intent {
        val uri = uriFor(context, file)
        return Intent(Intent.ACTION_SEND)
            .setPackage(PACKAGE)
            .setType(mimeType)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .apply { clipData = ClipData.newRawUri(file.name, uri) }
    }

    /** True when the Drive app is installed and can take a file. */
    fun isAvailable(context: Context): Boolean {
        val probe = Intent(Intent.ACTION_SEND).setPackage(PACKAGE).setType("application/octet-stream")
        return probe.resolveActivity(context.packageManager) != null
    }

    /** A backup is handed over only when it exists and is not empty. */
    fun isReady(file: File): Boolean = file.isFile && file.length() > 0L

    /**
     * Opens Drive's "Save to Drive" screen for [file]. Returns false (and does nothing) when the file is missing or
     * empty, or when the Drive app is not there; the caller then offers the file picker instead.
     */
    fun handOff(context: Context, file: File, mimeType: String): Boolean {
        if (!isReady(file)) return false
        return try {
            context.startActivity(sendIntent(context, file, mimeType).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    /** The same hand-over for a notification: tapping it opens Drive's screen (the grant is applied when sent). */
    fun pendingIntent(context: Context, file: File, mimeType: String): PendingIntent = PendingIntent.getActivity(
        context, 2, sendIntent(context, file, mimeType),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Where the restore picker should start: the Drive root, or null when the Drive app is missing or it fails. */
    fun restoreStartUri(context: Context): Uri? = try {
        if (isAvailable(context)) {
            DocumentsContract.buildDocumentUri(STORAGE_AUTHORITY, ROOT_DOCUMENT_ID)
        } else {
            null
        }
    } catch (_: RuntimeException) {
        null
    }
}

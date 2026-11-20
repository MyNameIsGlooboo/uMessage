package com.glooboo.umessage.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract
import java.io.File

/** A file the user is about to save: its [name] and [mimeType] for the system file picker. */
data class ExportFile(val name: String, val mimeType: String)

/** Opens the system "save as" picker for an [ExportFile] and returns where the user chose to save it, or null. */
class CreateExportDocument : ActivityResultContract<ExportFile, Uri?>() {
    override fun createIntent(context: Context, input: ExportFile): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(input.mimeType)
            .putExtra(Intent.EXTRA_TITLE, input.name)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        intent.takeIf { resultCode == android.app.Activity.RESULT_OK }?.data
}

/**
 * M-D3: the plain (not yet encrypted, not yet copied) temporary file of an export or a backup lives in one folder of
 * its own in the app's cache, which [wipe] empties at app start: a file left behind by a kill or a crash does not
 * stay on the phone. The documents say so (PRIVACY.md, docs/DATA-AT-REST.md).
 */
object ExportTempFolder {
    /** The folder under the app's cache. Not shared by the FileProvider. */
    const val DIR = "export_tmp"

    fun dir(context: Context): File = File(context.cacheDir, DIR).apply { mkdirs() }

    /** A new empty temporary file in the folder. */
    fun newFile(context: Context, prefix: String, suffix: String): File =
        File.createTempFile(prefix, suffix, dir(context))

    /**
     * Deletes every file in the folder last changed before [before] (the start of this process: a run that started
     * since keeps its file). Returns how many were deleted. Call off the main thread.
     */
    fun wipe(context: Context, before: Long): Int = File(context.cacheDir, DIR).listFiles().orEmpty()
        .count { it.isFile && it.lastModified() < before && it.delete() }
}

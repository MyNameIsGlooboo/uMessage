package com.glooboo.umessage.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract

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

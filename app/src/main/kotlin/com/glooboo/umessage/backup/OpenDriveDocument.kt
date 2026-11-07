package com.glooboo.umessage.backup

import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContracts

/**
 * The system "open a file" picker for "Restore from Google Drive": the same picker and the same result as
 * [ActivityResultContracts.OpenDocument], but it starts in Google Drive when the Drive app is installed. The start
 * location is only a hint; the picker ignores it when it does not resolve, so nothing can go wrong here.
 */
class OpenDriveDocument : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent {
        val intent = super.createIntent(context, input)
        DriveHandoff.restoreStartUri(context)?.let { intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
        return intent
    }
}

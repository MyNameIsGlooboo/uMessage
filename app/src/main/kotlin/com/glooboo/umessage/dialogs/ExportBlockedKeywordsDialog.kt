package com.glooboo.umessage.dialogs

import androidx.compose.ui.res.stringResource
import com.glooboo.umessage.R
import com.glooboo.umessage.extensions.config
import com.glooboo.umessage.helpers.BLOCKED_KEYWORDS_EXPORT_EXTENSION
import com.glooboo.umessage.ui.components.TextInputDialog
import com.glooboo.umessage.ui.components.showComposeDialog
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.extensions.getCurrentFormattedDateTime
import org.fossify.commons.extensions.getParentPath
import org.fossify.commons.extensions.internalStoragePath
import org.fossify.commons.extensions.isAValidFilename
import org.fossify.commons.helpers.ensureBackgroundThread
import java.io.File

/**
 * Asks for the export file name. The folder is chosen by the system file picker the host opens in [callback],
 * so [hidePath] (always true for the only caller) is kept for source compatibility and there is no folder field.
 */
class ExportBlockedKeywordsDialog(
    val activity: BaseSimpleActivity,
    val path: String,
    val hidePath: Boolean,
    callback: (file: File) -> Unit,
) {
    private val realPath = path.ifEmpty { activity.internalStoragePath }
    private val config = activity.config

    init {
        val initial = "${activity.getString(R.string.blocked_keywords)}_${activity.getCurrentFormattedDateTime()}"
        activity.showComposeDialog { dismiss ->
            val emptyError = stringResource(org.fossify.commons.R.string.empty_name)
            val invalidError = stringResource(org.fossify.commons.R.string.invalid_name)
            TextInputDialog(
                title = stringResource(R.string.export_blocked_keywords),
                initial = initial,
                label = stringResource(R.string.screens_dialog_filename),
                validate = {
                    when {
                        it.isEmpty() -> emptyError
                        !it.isAValidFilename() -> invalidError
                        else -> null
                    }
                },
                onConfirm = { filename ->
                    val file = File(realPath, "$filename$BLOCKED_KEYWORDS_EXPORT_EXTENSION")
                    ensureBackgroundThread {
                        config.lastBlockedKeywordExportPath = file.absolutePath.getParentPath()
                        callback(file)
                    }
                    dismiss()
                },
                onDismiss = dismiss,
            )
        }
    }
}

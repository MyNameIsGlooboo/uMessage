package com.glooboo.umessage.activities

import android.content.ActivityNotFoundException
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.glooboo.umessage.R
import com.glooboo.umessage.dialogs.ExportBlockedKeywordsDialog
import com.glooboo.umessage.extensions.config
import com.glooboo.umessage.extensions.toArrayList
import com.glooboo.umessage.helpers.BlockedKeywordsExporter
import com.glooboo.umessage.helpers.BlockedKeywordsFormat
import com.glooboo.umessage.ui.components.showUndo
import com.glooboo.umessage.ui.keywords.BlockedKeywordsScreen
import kotlinx.coroutines.launch
import org.fossify.commons.extensions.copyToClipboard
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ExportResult
import org.fossify.commons.helpers.ensureBackgroundThread
import java.io.OutputStream

/**
 * The old blocked words that could not become rules (see BlockingMigration): view, copy, export and delete only. New
 * blocking goes into the uMessage rules, so this list never grows from here.
 */
class ManageBlockedKeywordsActivity : ComposeSimpleActivity() {

    private var keywords by mutableStateOf<List<String>>(emptyList())
    private var loaded by mutableStateOf(false)
    private val snackbar = SnackbarHostState()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setUMessageContent {
            BlockedKeywordsScreen(
                keywords = keywords,
                loaded = loaded,
                snackbarHostState = snackbar,
                onBack = ::finish,
                onCopy = { copyToClipboard(it) },
                onDelete = ::deleteKeywords,
                onExport = ::tryExportBlockedNumbers,
            )
        }
        updateBlockedKeywords()
    }

    private fun deleteKeywords(toDelete: Set<String>) {
        toDelete.forEach { config.removeBlockedKeyword(it) }
        updateBlockedKeywords()
        lifecycleScope.launch {
            snackbar.showUndo(
                message = resources.getQuantityString(R.plurals.screens_keywords_deleted, toDelete.size, toDelete.size),
                undoLabel = getString(R.string.convlist_undo),
                onUndo = {
                    config.restoreBlockedKeywords(toDelete)
                    updateBlockedKeywords()
                },
            )
        }
    }

    private val createDocument =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            try {
                val outputStream = uri?.let { contentResolver.openOutputStream(it) }
                if (outputStream != null) {
                    exportBlockedKeywordsTo(outputStream)
                }
            } catch (e: Exception) {
                showErrorToast(e)
            }
        }

    private fun exportBlockedKeywordsTo(outputStream: OutputStream?) {
        ensureBackgroundThread {
            val blockedKeywords = config.blockedKeywords.toArrayList()
            if (blockedKeywords.isEmpty()) {
                toast(org.fossify.commons.R.string.no_entries_for_exporting)
            } else {
                BlockedKeywordsExporter.exportBlockedKeywords(blockedKeywords, outputStream) {
                    toast(
                        when (it) {
                            ExportResult.EXPORT_OK -> org.fossify.commons.R.string.exporting_successful
                            else -> org.fossify.commons.R.string.exporting_failed
                        },
                    )
                }
            }
        }
    }

    private fun tryExportBlockedNumbers() {
        ExportBlockedKeywordsDialog(
            activity = this,
            path = config.lastBlockedKeywordExportPath,
            hidePath = true,
        ) { file ->
            try {
                createDocument.launch(file.name)
            } catch (_: ActivityNotFoundException) {
                toast(
                    org.fossify.commons.R.string.system_service_disabled,
                    Toast.LENGTH_LONG,
                )
            } catch (e: Exception) {
                showErrorToast(e)
            }
        }
    }

    /**
     * Reads the list on the calling thread (preferences are in memory), so quick deletes cannot finish out of order
     * and show a stale list. Safe from background threads.
     */
    private fun updateBlockedKeywords() {
        val sorted = BlockedKeywordsFormat.sorted(config.blockedKeywords)
        runOnUiThread {
            keywords = sorted
            loaded = true
        }
    }
}

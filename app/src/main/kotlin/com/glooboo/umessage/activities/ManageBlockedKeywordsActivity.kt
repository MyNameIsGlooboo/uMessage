package com.glooboo.umessage.activities

import android.content.ActivityNotFoundException
import android.net.Uri
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
import com.glooboo.umessage.helpers.BlockedKeywordsImporter
import com.glooboo.umessage.ui.components.showUndo
import com.glooboo.umessage.ui.keywords.BlockedKeywordsScreen
import kotlinx.coroutines.launch
import org.fossify.commons.extensions.copyToClipboard
import org.fossify.commons.extensions.getTempFile
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ExportResult
import org.fossify.commons.helpers.ensureBackgroundThread
import java.io.InputStream
import java.io.OutputStream

@Suppress("TooManyFunctions")
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
                onSave = ::saveKeyword,
                onCopy = { copyToClipboard(it) },
                onDelete = ::deleteKeywords,
                onExport = ::tryExportBlockedNumbers,
                onImport = ::tryImportBlockedKeywords,
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
                    toDelete.forEach { config.addBlockedKeyword(it) }
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

    private val getContent =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            try {
                if (uri != null) {
                    tryImportBlockedKeywordsFromFile(uri)
                }
            } catch (e: Exception) {
                showErrorToast(e)
            }
        }

    private fun tryImportBlockedKeywords() {
        val mimeType = "text/plain"
        try {
            getContent.launch(mimeType)
        } catch (_: ActivityNotFoundException) {
            toast(org.fossify.commons.R.string.system_service_disabled, Toast.LENGTH_LONG)
        } catch (e: Exception) {
            showErrorToast(e)
        }
    }

    private fun tryImportBlockedKeywordsFromFile(uri: Uri) {
        when (uri.scheme) {
            "file" -> uri.path?.let(::importBlockedKeywords)
                ?: toast(org.fossify.commons.R.string.invalid_file_format)

            "content" -> importFromContent(uri)
            else -> toast(org.fossify.commons.R.string.invalid_file_format)
        }
    }

    /** Copies the picked file to a temp file off the main thread (closing both streams, size capped), then imports. */
    private fun importFromContent(uri: Uri) {
        val tempFile = getTempFile("blocked", "blocked_keywords.txt")
        if (tempFile == null) {
            toast(org.fossify.commons.R.string.unknown_error_occurred)
            return
        }
        ensureBackgroundThread {
            try {
                val input = contentResolver.openInputStream(uri) ?: error("No input stream")
                val copied = input.use { source ->
                    tempFile.outputStream().use { out -> source.copyAtMost(out, MAX_IMPORT_BYTES) }
                }
                if (copied) {
                    BlockedKeywordsImporter(this).importBlockedKeywords(tempFile.absolutePath).let(::showImportResult)
                    updateBlockedKeywords()
                } else {
                    toast(org.fossify.commons.R.string.invalid_file_format)
                }
            } catch (e: Exception) {
                showErrorToast(e)
            } finally {
                tempFile.delete()
            }
        }
    }

    private fun showImportResult(result: BlockedKeywordsImporter.ImportResult) {
        toast(
            when (result) {
                BlockedKeywordsImporter.ImportResult.IMPORT_OK -> org.fossify.commons.R.string.importing_successful
                BlockedKeywordsImporter.ImportResult.IMPORT_FAIL -> org.fossify.commons.R.string.no_items_found
            },
        )
    }

    private fun importBlockedKeywords(path: String) {
        ensureBackgroundThread {
            showImportResult(BlockedKeywordsImporter(this).importBlockedKeywords(path))
            updateBlockedKeywords()
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
     * Reads the list on the calling thread (preferences are in memory), so quick adds and deletes cannot finish out of
     * order and show a stale list. Safe from background threads.
     */
    private fun updateBlockedKeywords() {
        val sorted = BlockedKeywordsFormat.sorted(config.blockedKeywords)
        runOnUiThread {
            keywords = sorted
            loaded = true
        }
    }

    /** Adds [keyword], or renames [original] to it. A change of case only replaces the old spelling. */
    private fun saveKeyword(original: String?, keyword: String) {
        val cleaned = BlockedKeywordsFormat.clean(keyword) ?: return
        if (original != null && original != cleaned) config.removeBlockedKeyword(original)
        config.addBlockedKeyword(cleaned)
        updateBlockedKeywords()
    }
}

/** A keyword list is small: anything bigger than this is not one. */
private const val MAX_IMPORT_BYTES = 1_000_000L
private const val COPY_BUFFER_BYTES = 8 * 1024

/** Copies at most [limit] bytes; false (with the copy cut short) when the source has more. */
private fun InputStream.copyAtMost(out: OutputStream, limit: Long): Boolean {
    val buffer = ByteArray(COPY_BUFFER_BYTES)
    var total = 0L
    while (true) {
        val read = read(buffer)
        if (read < 0) return true
        total += read
        if (total > limit) return false
        out.write(buffer, 0, read)
    }
}

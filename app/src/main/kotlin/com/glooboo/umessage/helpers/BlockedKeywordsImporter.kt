package com.glooboo.umessage.helpers

import android.app.Activity
import org.fossify.commons.extensions.showErrorToast
import com.glooboo.umessage.extensions.config

import java.io.File

class BlockedKeywordsImporter(
    private val activity: Activity,
) {
    enum class ImportResult {
        IMPORT_FAIL, IMPORT_OK
    }

    /** Adds the file's keywords (see [BlockedKeywordsFormat.parse]); a file without any usable keyword fails. */
    fun importBlockedKeywords(path: String): ImportResult {
        return try {
            val keywords = BlockedKeywordsFormat.parse(File(path).readText())
            if (keywords.isEmpty()) return ImportResult.IMPORT_FAIL
            val config = activity.config
            config.blockedKeywords = keywords.fold(config.blockedKeywords) { set, keyword -> BlockedKeywordsFormat.add(set, keyword) }
            ImportResult.IMPORT_OK
        } catch (e: Exception) {
            activity.showErrorToast(e)
            ImportResult.IMPORT_FAIL
        }
    }
}

package com.glooboo.umessage.helpers

import org.fossify.commons.helpers.ExportResult
import java.io.OutputStream

object BlockedKeywordsExporter {

    fun exportBlockedKeywords(
        blockedKeywords: ArrayList<String>,
        outputStream: OutputStream?,
        callback: (result: ExportResult) -> Unit,
    ) {
        if (outputStream == null) {
            callback.invoke(ExportResult.EXPORT_FAIL)
            return
        }

        try {
            outputStream.bufferedWriter().use { out -> out.write(BlockedKeywordsFormat.export(blockedKeywords)) }
            callback.invoke(ExportResult.EXPORT_OK)
        } catch (e: Exception) {
            callback.invoke(ExportResult.EXPORT_FAIL)
        }
    }
}

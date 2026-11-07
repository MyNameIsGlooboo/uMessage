package com.glooboo.umessage.databases

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * DA2: brings the labels of a downgrade backup back without id collisions. Labels have generated ids, and the live
 * database started empty after the downgrade, so its ids restart at 1 and may be the ids of other labels in the
 * backup. The labels are merged by content ([DatabaseBackups.mergeRows], a colliding label gets a new id) and each
 * conversation link is re-pointed at the live label with the same content, never at whatever has that id now.
 */
internal object LabelRecovery {

    /** Merges `src.labels` into `main.labels` and copies `src.conversation_labels` through the id mapping. */
    fun merge(db: SupportSQLiteDatabase) {
        if (!DatabaseBackups.mergeRows(db, "labels")) return
        val shared = DatabaseBackups.sharedColumns(db, "labels")?.filter { it != "id" }.orEmpty()
        if (DatabaseBackups.sharedColumns(db, "conversation_labels") == null) return
        val same = shared.joinToString(" AND ") { "m.`$it` IS l.`$it`" }.ifEmpty { "1" }
        db.execSQL(
            "INSERT OR IGNORE INTO main.conversation_labels (thread_id, label_id) " +
                "SELECT s.thread_id, m.id FROM src.conversation_labels s " +
                "JOIN src.labels l ON l.id = s.label_id JOIN main.labels m ON $same"
        )
    }
}

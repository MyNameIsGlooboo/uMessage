package com.glooboo.umessage.helpers

import com.glooboo.umessage.models.AppDataBackup
import com.glooboo.umessage.models.MessagesBackup
import com.glooboo.umessage.models.MmsBackup
import com.glooboo.umessage.models.NotesBackup
import com.glooboo.umessage.models.SmsBackup
import kotlinx.serialization.json.Json
import java.io.Writer

/** Writes the messages as one JSON array, one entry at a time (the format Fossify Messages also writes). */
class JsonEntrySink(private val writer: Writer) : BackupEntrySink {
    private val json = Json { encodeDefaults = true }
    private var first = true

    override fun begin(count: Int) {
        writer.write("[")
    }

    override fun write(entry: MessagesBackup) {
        if (!first) writer.write(",")
        first = false
        writer.write(
            when (entry) {
                is SmsBackup -> json.encodeToString(SmsBackup.serializer(), entry)
                is MmsBackup -> json.encodeToString(MmsBackup.serializer(), entry)
                is NotesBackup -> json.encodeToString(NotesBackup.serializer(), entry)
                is AppDataBackup -> json.encodeToString(AppDataBackup.serializer(), entry)
            }
        )
    }

    override fun end() {
        writer.write("]")
        writer.flush()
    }
}

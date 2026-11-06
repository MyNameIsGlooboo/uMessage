package com.glooboo.umessage.helpers

import com.glooboo.umessage.models.MessagesBackup

/** Where [MessagesReader] sends the messages it reads: one implementation per file format. */
interface BackupEntrySink {
    /** Called once before the first message; [count] is how many will follow, or -1 when not asked for. */
    fun begin(count: Int)

    fun write(entry: MessagesBackup)

    fun end()
}

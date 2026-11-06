package com.glooboo.umessage.spam

import android.content.Context
import java.io.File

/**
 * Service locator for the spam engine (the app has no DI framework).
 * Implementations are provided by the engine agent; until then both getters throw.
 */
object UMessage {
    @Volatile var filterFactory: ((Context) -> SpamFilter)? = null
    @Volatile var repositoryFactory: ((Context) -> SpamRepository)? = null

    @Volatile private var spamLog: SpamLog? = null

    /** Local diagnostics log of the last 200 filter decisions of the past week (no message bodies). */
    fun log(context: Context): SpamLog = spamLog ?: synchronized(this) {
        spamLog ?: SpamLog(File(context.applicationContext.filesDir, "spamless_decisions.log")).also { spamLog = it }
    }

    fun filter(context: Context): SpamFilter =
        (filterFactory ?: error("SpamFilter not installed")).invoke(context.applicationContext)

    fun repository(context: Context): SpamRepository =
        (repositoryFactory ?: error("SpamRepository not installed")).invoke(context.applicationContext)
}

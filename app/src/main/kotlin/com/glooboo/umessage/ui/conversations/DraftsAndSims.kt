package com.glooboo.umessage.ui.conversations

import android.app.Application
import com.glooboo.umessage.databases.MessagesDatabase
import com.glooboo.umessage.extensions.getAllDrafts
import com.glooboo.umessage.ui.spam.SimDirectory
import com.glooboo.umessage.ui.spam.SimInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The drafts and SIM chips of the chat list, for [ConversationsViewModel]. [drafts] and [sims] are owned by the main
 * thread; every change calls [onChange], which asks the list for a rebuild.
 *
 * J2: the drafts are read again only when the drafts table changed, and after a publish only the thread SIMs are read,
 * only with two SIMs.
 */
internal class DraftsAndSims(
    private val app: Application,
    private val scope: CoroutineScope,
    private val onChange: () -> Unit,
) {
    var drafts: Map<Long, String> = emptyMap()
        private set

    var sims: List<SimInfo> = emptyList()
        private set

    /** SIM of each thread's newest message (sent or received), so chips show for every conversation. */
    @Volatile
    var threadSims: Map<Long, Int> = emptyMap()
        private set

    private var draftsJob: Job? = null

    /** True while [watchDrafts] hears every change of the drafts table; then a resume does not read them again. */
    @Volatile
    private var draftsWatched = false

    /** When the view model is made: the SIMs, then the drafts and their watch. */
    fun start() {
        refreshSims()
        watchDrafts()
    }

    /** Stops watching the drafts table: when the model is cleared, and in tests before they close the database. */
    fun stop() {
        draftsJob?.cancel()
    }

    /** The screen resumed: the SIMs, and the drafts only when they are not watched. */
    fun refresh() {
        if (!draftsWatched) refreshDrafts()
        refreshSims()
    }

    /**
     * After a publish only the thread SIMs, and only with two SIMs. The SIM list is kept from the last start or
     * resume, and deleted chats leave the kept map then too (that reads every saved row).
     */
    fun afterPublish() {
        if (sims.size >= 2) loadSims(reloadSims = false)
    }

    /**
     * The drafts are read once, then again only when the drafts table changed (the chat screen saves them), not on
     * every resume. When the watch fails, each resume reads them as before.
     */
    private fun watchDrafts() {
        draftsJob = scope.launch {
            try {
                val tracker = withContext(Dispatchers.IO) { MessagesDatabase.getInstance(app).invalidationTracker }
                tracker.createFlow(DRAFTS_TABLE)
                    .flowOn(Dispatchers.IO)
                    .collect {
                        draftsWatched = true
                        refreshDrafts()
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                draftsWatched = false
                refreshDrafts()
            }
        }
    }

    /** Reloads drafts; on a change of the drafts table, or on resume when they are not watched. */
    private fun refreshDrafts() {
        scope.launch {
            val fresh = withContext(Dispatchers.IO) {
                runCatching { app.getAllDrafts().toMap() }.getOrDefault(emptyMap())
            }
            if (fresh != drafts) {
                drafts = fresh
                onChange()
            }
        }
    }

    /** MC-18: the thread SIMs are read only with two SIMs, and then only messages newer than the last read. */
    private fun refreshSims() = loadSims(reloadSims = true)

    private fun loadSims(reloadSims: Boolean) {
        val known = sims
        scope.launch {
            val (freshSims, freshThreadSims) = withContext(Dispatchers.IO) {
                val loaded = if (reloadSims) runCatching { SimDirectory.load(app) }.getOrDefault(emptyList()) else known
                val live: () -> Set<Long>? = { if (reloadSims) runCatching { liveChatIds(app) }.getOrNull() else null }
                loaded to runCatching { ThreadSimCache.get(app).load(app, loaded.size, live) }.getOrDefault(emptyMap())
            }
            if (freshSims != sims || freshThreadSims != threadSims) {
                sims = freshSims
                threadSims = freshThreadSims
                onChange()
            }
        }
    }

    private companion object {
        /** The Room table of [com.glooboo.umessage.models.Draft]. */
        const val DRAFTS_TABLE = "drafts"
    }
}

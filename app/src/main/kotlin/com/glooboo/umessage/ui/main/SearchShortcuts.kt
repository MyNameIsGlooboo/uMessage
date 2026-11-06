package com.glooboo.umessage.ui.main

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.glooboo.umessage.R
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.extensions.messagesDB
import com.glooboo.umessage.helpers.HiddenChats
import com.glooboo.umessage.helpers.LinkExtractor
import com.glooboo.umessage.helpers.StarredMessages
import com.glooboo.umessage.helpers.isUnknownSender
import com.glooboo.umessage.helpers.starredDB
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.models.SearchResult
import com.glooboo.umessage.models.StarredMessage
import com.glooboo.umessage.spam.engine.NumberNormalizer
import com.glooboo.umessage.spam.engine.SystemContactChecker
import com.glooboo.umessage.spam.engine.defaultRegion
import com.glooboo.umessage.ui.components.displayTitle
import com.glooboo.umessage.ui.components.formatListTimeStyled
import com.glooboo.umessage.ui.conversations.ConversationRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * I28: what the Unknown, Starred, Images and Links tiles show. Each reads the message cache (or the stars) on the IO
 * thread, never the whole inbox in memory: SQL prefilters the rows, a Kotlin pass keeps the real matches, and at most
 * [LIMIT] come back, newest first. [images] and [links] are shared with the conversation's media list (I56).
 */
internal object SearchShortcuts {

    const val LIMIT = 500
    const val CANDIDATES = 2000
    private const val MILLIS = 1000L
    private const val LINKS_IN_SNIPPET = 2

    private val extractor = LinkExtractor()

    @Volatile
    private var contacts: SystemContactChecker? = null

    /** The messages of [candidates] that hold a picture. */
    fun images(candidates: List<Message>): List<Message> =
        candidates.filter { message ->
            message.attachment?.attachments.orEmpty().any { it.mimetype.startsWith("image/", ignoreCase = true) }
        }.take(LIMIT)

    /** The messages of [candidates] whose text holds a web link. */
    fun links(candidates: List<Message>, linkExtractor: LinkExtractor = extractor): List<Message> =
        candidates.filter { linkExtractor.hasLink(it.body) }.take(LIMIT)

    /** Unknown needs the contacts: without the permission the tile is hidden. */
    fun unknownAvailable(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    suspend fun load(context: Context, filter: InboxFilter, rows: List<ConversationRow>): ShortcutHits =
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            val byThread = rows.associateBy { it.threadId }
            runCatching {
                when (filter) {
                    InboxFilter.Unknown -> unknown(app, rows)
                    InboxFilter.Starred -> starred(app, byThread)
                    InboxFilter.Images -> {
                        val pictures = images(visible(app, app.messagesDB.getImageCandidates(CANDIDATES)))
                        fromMessages(filter, app, byThread, pictures) {
                            it.body.ifBlank { app.getString(R.string.i28_hit_picture) }
                        }
                    }
                    InboxFilter.Links -> {
                        val linked = links(visible(app, app.messagesDB.getLinkCandidates(CANDIDATES)))
                        fromMessages(filter, app, byThread, linked) {
                            extractor.extract(it.body).take(LINKS_IN_SNIPPET).joinToString("\n")
                        }
                    }
                    else -> ShortcutHits(filter, loaded = true)
                }
            }.getOrElse { ShortcutHits(filter, loaded = true) }
        }

    /** B06: the messages of [candidates] that do not belong to a hidden chat. */
    private fun visible(app: Context, candidates: List<Message>): List<Message> =
        HiddenChats.get(app).withoutHidden(candidates) { it.threadId }

    private fun unknown(app: Context, rows: List<ConversationRow>): ShortcutHits {
        val checker = contactChecker(app)
        val threads = if (!checker.canReadContacts()) {
            emptySet<Long>()
        } else {
            rows.filter { it.phoneNumber.isNotBlank() }.filter { row ->
                isUnknownSender(checker.lookup(row.phoneNumber), checker.sentTo(row.phoneNumber), row.isGroup)
            }.mapTo(HashSet()) { it.threadId }
        }
        return ShortcutHits(InboxFilter.Unknown, threads = threads, loaded = true)
    }

    private fun contactChecker(app: Context): SystemContactChecker = contacts ?: synchronized(this) {
        contacts ?: SystemContactChecker(app, NumberNormalizer { defaultRegion(app) }).also { contacts = it }
    }

    private suspend fun starred(app: Context, byThread: Map<Long, ConversationRow>): ShortcutHits {
        StarredMessages.relink(app)
        val stars = HiddenChats.get(app).withoutHidden(app.starredDB.getAll()) { it.threadId }
        val hits = stars.map { starHit(app, it, byThread[it.threadId]) }
        val threads = stars.mapTo(HashSet()) { it.threadId }
        val gone = threads.filterTo(HashSet()) { app.conversationsDB.getConversationWithThreadId(it) == null }
        return ShortcutHits(InboxFilter.Starred, threads, hits, gone, loaded = true)
    }

    private fun starHit(app: Context, star: StarredMessage, row: ConversationRow?) = SearchResult(
        messageId = star.id,
        title = row?.title ?: displayTitle(app, star.address),
        snippet = star.snapshotBody,
        date = formatListTimeStyled(app, star.date * MILLIS),
        threadId = star.threadId,
        photoUri = row?.photoUri.orEmpty(),
        isMMS = star.isMMS,
    )

    private fun fromMessages(
        filter: InboxFilter,
        app: Context,
        byThread: Map<Long, ConversationRow>,
        messages: List<Message>,
        snippet: (Message) -> String,
    ): ShortcutHits {
        val hits = messages.map { message ->
            val row = byThread[message.threadId]
            SearchResult(
                messageId = message.id,
                title = row?.title ?: displayTitle(app, message.senderName.ifBlank { message.senderPhoneNumber }),
                snippet = snippet(message),
                date = formatListTimeStyled(app, message.millis()),
                threadId = message.threadId,
                photoUri = row?.photoUri ?: message.senderPhotoUri,
                isMMS = message.isMMS,
            )
        }
        return ShortcutHits(filter, hits.mapTo(HashSet()) { it.threadId }, hits, loaded = true)
    }
}

package com.glooboo.umessage.helpers

import android.content.Context
import android.provider.Telephony
import androidx.compose.runtime.Immutable
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.models.MessageKey
import com.glooboo.umessage.models.StarredListRow
import com.glooboo.umessage.spam.TextNormalizer
import com.glooboo.umessage.ui.components.displayTitle as formatTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What a starred message holds besides text, for the row's second line. */
enum class StarredKind { TEXT, PICTURE, ATTACHMENT }

/** One row of the Starred screen: one starred message, whatever chat or sender it has. */
@Immutable
data class StarredItem(
    val id: Long,
    val isMMS: Boolean,
    val threadId: Long,
    val title: String,
    val photoUri: String,
    val text: String,
    val kind: StarredKind,
    /** The message time in milliseconds. */
    val dateMillis: Long,
    val hasNote: Boolean,
    /** The chat is gone from the app; only the text the star kept is left. */
    val chatGone: Boolean,
    val isGroup: Boolean,
) {
    val key: MessageKey
        get() = MessageKey(id, isMMS)

    /** Stable list key: the message, not the chat, so two stars of one sender are two rows. */
    val listKey: String
        get() = "star-$id-$isMMS"
}

/** A page of the Starred screen: [items] newest star first, and whether more stars are left to load. */
data class StarredPage(val items: List<StarredItem> = emptyList(), val hasMore: Boolean = false)

/**
 * The Starred screen's data: one [StarredItem] per starred message (never merged per chat), newest star first. The
 * mapping is pure so it can be tested without a database; [load] reads the stars, the chats and the notes.
 */
object StarredList {

    const val PAGE = 500
    private const val MILLIS = 1000L

    /**
     * [rows] in their order, with the chat of each in [chats] (a missing chat means it was deleted), and [noteKeys]
     * the note keys of the app ([MessageNoteKey]). [displayTitle] writes a name or number the way the lists show it.
     */
    fun build(
        rows: List<StarredListRow>,
        chats: Map<Long, Conversation>,
        noteKeys: Set<String>,
        displayTitle: (String) -> String,
    ): List<StarredItem> = rows.map { row ->
        val chat = chats[row.threadId]
        val text = row.liveBody ?: row.snapshotBody
        val attachments = row.liveAttachment?.attachments.orEmpty()
        val kind = when {
            attachments.any { it.mimetype.startsWith("image/", ignoreCase = true) } -> StarredKind.PICTURE
            // A picture message with no text whose parts are not cached would show an empty row otherwise.
            attachments.isNotEmpty() || (row.isMMS && text.isBlank()) -> StarredKind.ATTACHMENT
            else -> StarredKind.TEXT
        }
        StarredItem(
            id = row.id,
            isMMS = row.isMMS,
            threadId = row.threadId,
            title = displayTitle(chat?.title?.takeIf { it.isNotBlank() } ?: row.address),
            photoUri = chat?.photoUri.orEmpty(),
            text = text,
            kind = kind,
            dateMillis = row.date * MILLIS,
            hasNote = hasNote(row, text, noteKeys),
            chatGone = chat == null,
            isGroup = chat?.isGroupConversation == true,
        )
    }

    /** The note key depends on the direction; a message that is not cached has none known, so both are tried. */
    private fun hasNote(row: StarredListRow, text: String, noteKeys: Set<String>): Boolean {
        if (noteKeys.isEmpty()) return false
        val directions = when (row.liveType) {
            null -> listOf(true, false)
            Telephony.Sms.MESSAGE_TYPE_INBOX -> listOf(true)
            else -> listOf(false)
        }
        return directions.any { received ->
            MessageNoteKey.of(row.address, row.date.toInt(), received, text, row.isMMS) in noteKeys
        }
    }

    /** The items whose text or chat name hold [query], read lower-cased and folded (Persian and Arabic forms agree). */
    fun filter(items: List<StarredItem>, query: String): List<StarredItem> {
        val wanted = TextNormalizer.lower(query.trim())
        if (wanted.isEmpty()) return items
        return items.filter {
            TextNormalizer.lower(it.text).contains(wanted) || TextNormalizer.lower(it.title).contains(wanted)
        }
    }

    /** Reads up to [limit] stars with their chats and notes. Stars of hidden chats stay out, like in search. */
    suspend fun load(context: Context, limit: Int = PAGE): StarredPage = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val rows = app.starredDB.getListRows(limit + 1)
        val visible = HiddenChats.get(app).withoutHidden(rows.take(limit)) { it.threadId }
        val chats = visible.map { it.threadId }.distinct().mapNotNull { id ->
            app.conversationsDB.getConversationWithThreadId(id)
        }.associateBy { it.threadId }
        val noteKeys = app.messageNotesDB.getAllKeys().toHashSet()
        StarredPage(build(visible, chats, noteKeys) { formatTitle(app, it) }, hasMore = rows.size > limit)
    }
}

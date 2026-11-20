package com.glooboo.umessage.extensions

import android.annotation.SuppressLint
import android.app.Application
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteException
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract.PhoneLookup
import android.provider.OpenableColumns
import android.provider.Telephony.Mms
import android.provider.Telephony.MmsSms
import android.provider.Telephony.Sms
import android.provider.Telephony.Threads
import android.provider.Telephony.ThreadsColumns
import android.telephony.SubscriptionManager
import android.text.TextUtils
import androidx.core.net.toUri
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.google.android.mms.pdu_alt.PduHeaders
import org.fossify.commons.extensions.areDigitsOnly
import org.fossify.commons.extensions.getBlockedNumbers
import org.fossify.commons.extensions.getIntValue
import org.fossify.commons.extensions.getIntValueOr
import org.fossify.commons.extensions.getLongValue
import org.fossify.commons.extensions.getMyContactsCursor
import org.fossify.commons.extensions.getStringValue
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.extensions.isNumberBlocked
import org.fossify.commons.extensions.normalizeString
import org.fossify.commons.extensions.notificationManager
import org.fossify.commons.extensions.queryCursor
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.DAY_SECONDS
import org.fossify.commons.helpers.MONTH_SECONDS
import org.fossify.commons.helpers.MyContactsContentProvider
import org.fossify.commons.helpers.PERMISSION_READ_CONTACTS
import org.fossify.commons.helpers.SimpleContactsHelper
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.helpers.isQPlus
import org.fossify.commons.models.PhoneNumber
import org.fossify.commons.models.SimpleContact
import com.glooboo.umessage.R
import com.glooboo.umessage.databases.MessagesDatabase
import com.glooboo.umessage.helpers.ArchiveStore
import com.glooboo.umessage.helpers.AttachmentUtils.parseAttachmentNames
import com.glooboo.umessage.helpers.Config
import com.glooboo.umessage.helpers.DefaultSmsRole
import com.glooboo.umessage.helpers.NotifyContext
import com.glooboo.umessage.helpers.NotifyLog
import com.glooboo.umessage.helpers.NotifyResult
import com.glooboo.umessage.helpers.stripControlCharacters
import com.glooboo.umessage.helpers.ConversationLookups
import com.glooboo.umessage.helpers.FILE_SIZE_NONE
import com.glooboo.umessage.helpers.MAX_MESSAGE_LENGTH
import com.glooboo.umessage.helpers.MESSAGES_LIMIT
import com.glooboo.umessage.helpers.MessageCacheImport
import com.glooboo.umessage.helpers.MessageNoteKey
import com.glooboo.umessage.helpers.messageNotes
import com.glooboo.umessage.helpers.MessagingCache
import com.glooboo.umessage.helpers.NotificationHelper
import com.glooboo.umessage.helpers.ProviderTruthFlag
import com.glooboo.umessage.helpers.ShortcutHelper
import com.glooboo.umessage.helpers.generateRandomId
import com.glooboo.umessage.interfaces.AttachmentsDao
import com.glooboo.umessage.interfaces.ConversationsDao
import com.glooboo.umessage.interfaces.DraftsDao
import com.glooboo.umessage.interfaces.LabelsDao
import com.glooboo.umessage.interfaces.MessageAttachmentsDao
import com.glooboo.umessage.interfaces.MessagesDao
import com.glooboo.umessage.messaging.IncomingDateSent
import com.glooboo.umessage.messaging.MessagingUtils
import com.glooboo.umessage.messaging.MessagingUtils.Companion.ADDRESS_SEPARATOR
import com.glooboo.umessage.messaging.ScheduledAction
import com.glooboo.umessage.messaging.ScheduledSendClaims
import com.glooboo.umessage.messaging.SmsSender
import com.glooboo.umessage.messaging.scheduledActionFor
import com.glooboo.umessage.messaging.cancelRemindersForMessage
import com.glooboo.umessage.messaging.cancelRemindersForThread
import com.glooboo.umessage.messaging.holdDiedScheduledSend
import com.glooboo.umessage.messaging.scheduleMessage
import com.glooboo.umessage.models.Attachment
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.models.Draft
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.models.MessageAttachment
import com.glooboo.umessage.models.MessageKey
import com.glooboo.umessage.models.NamePhoto
import com.glooboo.umessage.models.RecycleBinMessage
import com.glooboo.umessage.helpers.SCHEDULED_ID_MIN
import com.glooboo.umessage.spam.FirstRun
import com.glooboo.umessage.spam.NewChatSorting
import com.glooboo.umessage.spam.SpamSorting
import com.glooboo.umessage.spam.SpamThreads
import com.glooboo.umessage.spam.engine.NumberNormalizer
import com.glooboo.umessage.spam.engine.defaultRegion
import org.xmlpull.v1.XmlPullParserException
import java.io.FileNotFoundException
import kotlin.time.Duration.Companion.minutes

val Context.config: Config
    get() = Config.newInstance(applicationContext)

fun Context.getMessagesDB() = MessagesDatabase.getInstance(this)

val Context.conversationsDB: ConversationsDao
    get() = getMessagesDB().ConversationsDao()

val Context.attachmentsDB: AttachmentsDao
    get() = getMessagesDB().AttachmentsDao()

val Context.messageAttachmentsDB: MessageAttachmentsDao
    get() = getMessagesDB().MessageAttachmentsDao()

val Context.messagesDB: MessagesDao
    get() = getMessagesDB().MessagesDao()

val Context.draftsDB: DraftsDao
    get() = getMessagesDB().DraftsDao()

val Context.labelsDB: LabelsDao
    get() = getMessagesDB().LabelsDao()

val Context.notificationHelper
    get() = NotificationHelper(this)

val Context.messagingUtils
    get() = MessagingUtils(this)

val Context.smsSender
    get() = SmsSender.getInstance(applicationContext as Application)

val Context.shortcutHelper get() = ShortcutHelper(this)

/**
 * The newest [limit] messages of a thread (SMS, MMS and, unless excluded, scheduled ones), oldest first.
 *
 * With [dateFrom] (Unix seconds) only messages older than that second are read; [includeSameSecond] also takes the
 * ones sent within that second. Paging needs it (DL-17): a message's date is kept in whole seconds, so the oldest
 * message on screen does not tell which of the others sent in that same second were already loaded.
 */
fun Context.getMessages(
    threadId: Long,
    dateFrom: Int = -1,
    includeScheduledMessages: Boolean = true,
    limit: Int = MESSAGES_LIMIT,
    includeSameSecond: Boolean = false,
): ArrayList<Message> {
    val uri = Sms.CONTENT_URI
    val projection = arrayOf(
        Sms._ID,
        Sms.BODY,
        Sms.TYPE,
        Sms.ADDRESS,
        Sms.DATE,
        Sms.READ,
        Sms.THREAD_ID,
        Sms.SUBSCRIPTION_ID,
        Sms.STATUS
    )

    // SMS dates are in milliseconds, MMS dates in seconds; both are read up to the end of the cutoff second when
    // includeSameSecond is set.
    val beforeSecond = if (dateFrom != -1 && includeSameSecond) dateFrom + 1 else dateFrom
    val rangeQuery = if (dateFrom == -1) "" else "AND ${Sms.DATE} < ${beforeSecond.toLong() * 1000}"
    val selection = "${Sms.THREAD_ID} = ? $rangeQuery"
    val selectionArgs = arrayOf(threadId.toString())
    val sortOrder = "${Sms.DATE} DESC LIMIT $limit"

    // Messages from a number that is on the system block list are shown too: blocking stops new messages, it does
    // not hide the history that is already in the thread (and still counted as unread).
    var messages = ArrayList<Message>()
    queryCursor(uri, projection, selection, selectionArgs, sortOrder, showErrors = true) { cursor ->
        val senderNumber = cursor.getStringValue(Sms.ADDRESS) ?: return@queryCursor

        val id = cursor.getLongValue(Sms._ID)
        val body = cursor.getStringValue(Sms.BODY)
        val type = cursor.getIntValue(Sms.TYPE)
        val namePhoto = getNameAndPhotoFromPhoneNumber(senderNumber)
        val senderName = namePhoto.name
        val photoUri = namePhoto.photoUri ?: ""
        val date = (cursor.getLongValue(Sms.DATE) / 1000).toInt()
        val read = cursor.getIntValue(Sms.READ) == 1
        val thread = cursor.getLongValue(Sms.THREAD_ID)
        val subscriptionId = cursor.getIntValueOr(
            key = Sms.SUBSCRIPTION_ID,
            defaultValue = SubscriptionManager.INVALID_SUBSCRIPTION_ID
        )

        val status = cursor.getIntValue(Sms.STATUS)
        val participants = senderNumber.split(ADDRESS_SEPARATOR).map { number ->
            val phoneNumber = PhoneNumber(number, 0, "", number)
            val participantPhoto = getNameAndPhotoFromPhoneNumber(number)
            SimpleContact(
                rawId = 0,
                contactId = 0,
                name = participantPhoto.name,
                photoUri = photoUri,
                phoneNumbers = arrayListOf(phoneNumber),
                birthdays = ArrayList(),
                anniversaries = ArrayList()
            )
        }
        val isMMS = false
        val message =
            Message(
                id = id,
                body = body,
                type = type,
                status = status,
                participants = ArrayList(participants),
                date = date,
                read = read,
                threadId = thread,
                isMMS = isMMS,
                attachment = null,
                senderPhoneNumber = senderNumber,
                senderName = senderName,
                senderPhotoUri = photoUri,
                subscriptionId = subscriptionId
            )
        messages.add(message)
    }

    messages.addAll(getMMS(threadId, sortOrder, beforeSecond))

    if (includeScheduledMessages) {
        try {
            val scheduledMessages = messagesDB.getScheduledThreadMessages(threadId)
            messages.addAll(scheduledMessages)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    messages = messages
        .filter { it.participants.isNotEmpty() }
        .filterNot { it.isExpiredScheduled(System.currentTimeMillis()) }
        .sortedWith(compareBy<Message> { it.date }.thenBy { it.id })
        .takeLast(limit)
        .toMutableList() as ArrayList<Message>

    return messages
}

/**
 * The next page of a thread's history when [loaded] is on screen and its oldest message is from second [cutoff]:
 * the messages not loaded yet from that second and before, oldest first. Empty when nothing older is left.
 *
 * DL-17: the old `date < cutoff` page skipped every message sent in the same second as the oldest one shown.
 * Scheduled messages are left out: they are the newest of all and come with the regular refresh.
 */
fun Context.getOlderThreadMessages(threadId: Long, cutoff: Int, loaded: List<Message>): List<Message> {
    val page = getMessages(threadId, cutoff, includeScheduledMessages = false, includeSameSecond = true)
        .filterNotInByKey(loaded) { it.getStableId() }
    if (page.isNotEmpty()) return page
    // A whole page from the cutoff second was already on screen; step past that second so paging keeps moving.
    return getMessages(threadId, cutoff, includeScheduledMessages = false)
        .filterNotInByKey(loaded) { it.getStableId() }
}

// as soon as a message contains multiple recipients it counts as an MMS instead of SMS
fun Context.getMMS(
    threadId: Long? = null,
    sortOrder: String? = null,
    dateFrom: Int = -1,
    uri: Uri = Mms.CONTENT_URI,
): ArrayList<Message> {
    val projection = arrayOf(
        Mms._ID,
        Mms.DATE,
        Mms.READ,
        Mms.MESSAGE_BOX,
        Mms.THREAD_ID,
        Mms.SUBSCRIPTION_ID,
        Mms.STATUS
    )

    var selection: String? = null
    var selectionArgs: Array<String>? = null

    if (threadId == null && dateFrom != -1) {
        // Should not multiply 1000 here, because date in mms's database is different from sms's.
        selection = "${Sms.DATE} < ${dateFrom.toLong()}"
    } else if (threadId != null && dateFrom == -1) {
        selection = "${Sms.THREAD_ID} = ?"
        selectionArgs = arrayOf(threadId.toString())
    } else if (threadId != null) {
        selection = "${Sms.THREAD_ID} = ? AND ${Sms.DATE} < ${dateFrom.toLong()}"
        selectionArgs = arrayOf(threadId.toString())
    }

    // DL-19: the rows are read first and their parts in one bulk query afterwards. Each MMS used to cost its own
    // parts query, sender query and participant lookups, hundreds of provider calls for a chat with many pictures.
    val rows = ArrayList<MmsRow>()
    queryCursor(uri, projection, selection, selectionArgs, sortOrder, showErrors = true) { cursor ->
        rows += MmsRow(
            id = cursor.getLongValue(Mms._ID),
            type = cursor.getIntValue(Mms.MESSAGE_BOX),
            date = cursor.getLongValue(Mms.DATE).toInt(),
            read = cursor.getIntValue(Mms.READ) == 1,
            threadId = cursor.getLongValue(Mms.THREAD_ID),
            subscriptionId = cursor.getIntValue(Mms.SUBSCRIPTION_ID),
            status = cursor.getIntValue(Mms.STATUS),
        )
    }
    if (rows.isEmpty()) return ArrayList()

    val attachments = getMmsAttachments(rows.map { it.id })
    val contactsMap = HashMap<Int, SimpleContact>()
    val participantsByThread = HashMap<Long, ArrayList<SimpleContact>>()
    val messages = ArrayList<Message>(rows.size)
    for (row in rows) {
        val participants = participantsByThread.getOrPut(row.threadId) {
            getThreadParticipants(row.threadId, contactsMap).also { found ->
                found.forEach { contactsMap[it.rawId] = it }
            }
        }

        val attachment = attachments[row.id] ?: MessageAttachment(row.id, "", arrayListOf())
        var senderNumber = ""
        var senderName = ""
        var senderPhotoUri = ""

        if (row.type != Mms.MESSAGE_BOX_SENT && row.type != Mms.MESSAGE_BOX_FAILED) {
            // In a one-to-one chat the sender is the other person; only group messages need their own lookup.
            senderNumber = participants.singleOrNull()?.phoneNumbers?.firstOrNull()?.value?.takeIf { it.isNotEmpty() }
                ?: getMMSSender(row.id)
            val namePhoto = getNameAndPhotoFromPhoneNumber(senderNumber)
            senderName = namePhoto.name
            senderPhotoUri = namePhoto.photoUri ?: ""
        }

        messages += Message(
            id = row.id,
            body = attachment.text,
            type = row.type,
            status = row.status,
            participants = ArrayList(participants),
            date = row.date,
            read = row.read,
            threadId = row.threadId,
            isMMS = true,
            attachment = attachment,
            senderPhoneNumber = senderNumber,
            senderName = senderName,
            senderPhotoUri = senderPhotoUri,
            subscriptionId = row.subscriptionId
        )
    }

    return messages
}

fun Context.getMMSSender(msgId: Long): String {
    val uri = "${Mms.CONTENT_URI}/$msgId/addr".toUri()
    val projection = arrayOf(
        Mms.Addr.ADDRESS
    )

    val selection = "${Mms.Addr.TYPE} = ?"
    val selectionArgs = arrayOf(PduHeaders.FROM.toString())

    try {
        val cursor = contentResolver.query(uri, projection, selection, selectionArgs, null)
        cursor?.use {
            if (it.moveToFirst()) {
                return it.getStringValue(Mms.Addr.ADDRESS)
            }
        }
    } catch (_: Exception) {
    }
    return ""
}

/** Unread incoming messages per thread; only [threadId]'s when it is given (one chat refreshed, MC-02). */
fun Context.getUnreadCountsByThread(threadId: Long? = null): Map<Long, Int> {
    val result = HashMap<Long, Int>(128)

    fun bump(id: Long) {
        result[id] = (result[id] ?: 0) + 1
    }

    val inThread = if (threadId != null) " AND thread_id = ?" else ""
    val args = threadId?.let { arrayOf(it.toString()) }

    // Unread SMS
    queryCursor(
        uri = Sms.CONTENT_URI,
        projection = arrayOf(Sms.THREAD_ID),
        selection = "${Sms.READ}=0 AND ${Sms.TYPE}=${Sms.MESSAGE_TYPE_INBOX}$inThread",
        selectionArgs = args,
        showErrors = false
    ) { bump(it.getLongValue(Sms.THREAD_ID)) }

    // Unread MMS
    queryCursor(
        uri = Mms.CONTENT_URI,
        projection = arrayOf(Mms.THREAD_ID),
        selection = "${Mms.READ}=0 AND ${Mms.MESSAGE_BOX}=${Mms.MESSAGE_BOX_INBOX}$inThread",
        selectionArgs = args,
        showErrors = false
    ) { bump(it.getLongValue(Mms.THREAD_ID)) }

    return result
}

/**
 * Conversations from the telephony provider, newest first. [limit] stops after that many conversations are kept,
 * so the top of the list can be refreshed before the full pass. Pass the same [lookups] to several calls to read
 * contacts and addresses only once.
 */
fun Context.getConversations(
    threadId: Long? = null,
    privateContacts: ArrayList<SimpleContact> = ArrayList(),
    limit: Int? = null,
    lookups: ConversationLookups = ConversationLookups(this, bulk = threadId == null),
    /** B-16: also chats of numbers on Android's block list; null: as the lists show them now. */
    includeBlocked: Boolean? = null,
): ArrayList<Conversation> =
    readConversations(threadId, privateContacts, limit, lookups, includeBlocked ?: listsShowBlocked()).first

/**
 * Like [getConversations], but null when the provider could not be read completely (no cursor, or an error part
 * way through). A caller that deletes cached threads missing from the result must use this: a failed read is not
 * "every conversation was deleted". With [threadId], only that thread (empty: the store does not list it).
 */
fun Context.getConversationsOrNull(
    threadId: Long? = null,
    privateContacts: ArrayList<SimpleContact> = ArrayList(),
    lookups: ConversationLookups = ConversationLookups(this, bulk = threadId == null),
    /** B-16: also chats of numbers on Android's block list (the sorter and the history check ask for them). */
    includeBlocked: Boolean? = null,
): ArrayList<Conversation>? {
    val blocked = includeBlocked ?: listsShowBlocked()
    val (conversations, complete) = readConversations(threadId, privateContacts, null, lookups, blocked)
    return conversations.takeIf { complete }
}

/**
 * B-16: the lists show the chats of numbers on Android's block list (in Spam, as blocked) only once the first sort is
 * published; before that, and with the flag off, they are left out as in 0.4.14.
 */
private fun Context.listsShowBlocked(): Boolean =
    ProviderTruthFlag.enabled && runCatching { FirstRun.state(this).value.sortPublished }.getOrDefault(false)

/** The conversations read, and whether the read finished without the provider failing. */
private fun Context.readConversations(
    threadId: Long?,
    privateContacts: ArrayList<SimpleContact>,
    limit: Int?,
    lookups: ConversationLookups,
    includeBlocked: Boolean,
): Pair<ArrayList<Conversation>, Boolean> {
    val archiveAvailable = config.isArchiveAvailable
    val archiveTrusted = ArchiveStore.isTrusted(this)

    val uri = "${Threads.CONTENT_URI}?simple=true".toUri()
    val projection = mutableListOf(
        Threads._ID,
        Threads.SNIPPET,
        Threads.DATE,
        Threads.RECIPIENT_IDS,
    )

    if (archiveAvailable) {
        projection += Threads.ARCHIVED
    }

    var selection = "${Threads.MESSAGE_COUNT} > 0"
    var selectionArgs = arrayOf<String>()
    if (threadId != null) {
        selection += " AND ${Threads._ID} = ?"
        selectionArgs += threadId.toString()
    }

    val sortOrder = "${Threads.DATE} DESC"

    val conversations = ArrayList<Conversation>()
    val blockedNumbers = getBlockedNumbers()
    val read = ThreadRead(
        lookups = lookups,
        privateContacts = privateContacts,
        unreadMap = getUnreadCountsByThread(threadId),
        markedUnread = config.markedUnreadThreads,
        archiveTrusted = archiveTrusted,
        // S3: a thread without recipients is always left out. 0.4.14: a group stays listed unless every member is
        // blocked; a 1:1 chat with a blocked number is left out. Provider truth lists it (it is shown in Spam).
        hideBlocked = !includeBlocked,
        isBlocked = { isNumberBlocked(it, blockedNumbers) },
    )
    var complete = false
    try {
        complete = queryCursorUnsafe(
            uri,
            projection.toTypedArray(),
            selection,
            selectionArgs,
            sortOrder,
            shouldStop = { limit != null && conversations.size >= limit },
        ) { cursor ->
            conversationOf(cursor, read)?.let { conversations.add(it) }
        }
    } catch (sqliteException: SQLiteException) {
        if (
            sqliteException.message?.contains("no such column: archived") == true
            && archiveAvailable
        ) {
            config.isArchiveAvailable = false
            return readConversations(threadId, privateContacts, limit, lookups, includeBlocked)
        } else {
            showErrorToast(sqliteException)
        }
    } catch (e: Exception) {
        showErrorToast(e)
    }

    conversations.sortByDescending { it.date }
    return conversations to complete
}

/** What [readConversations] reads once and every thread row needs. */
private class ThreadRead(
    val lookups: ConversationLookups,
    val privateContacts: ArrayList<SimpleContact>,
    val unreadMap: Map<Long, Int>,
    val markedUnread: Set<String>,
    val archiveTrusted: Boolean,
    val hideBlocked: Boolean,
    val isBlocked: (String) -> Boolean,
)

/** One thread row of the provider as a chat; null when the list leaves it out (no recipients, or blocked). */
private fun Context.conversationOf(cursor: Cursor, read: ThreadRead): Conversation? {
    val lookups = read.lookups
    val id = cursor.getLongValue(Threads._ID)
    var snippet = cursor.getStringValue(Threads.SNIPPET) ?: ""
    if (snippet.isEmpty()) {
        snippet = getThreadSnippet(id)
    }

    var date = cursor.getLongValue(Threads.DATE)
    if (date.toString().length > 10) {
        date /= 1000
    }

    // drafts are stored locally they take priority over the original date
    val draft = lookups.draft(id)
    if (draft != null) {
        date = draft.date / 1000
    }

    val rawIds = cursor.getStringValue(Threads.RECIPIENT_IDS)
    val recipientIds =
        rawIds.split(" ").filter { it.areDigitsOnly() }.map { it.toInt() }.toMutableList()
    val phoneNumbers = recipientIds.mapTo(ArrayList()) { lookups.phoneNumber(it) }
    if (isThreadHiddenByBlocks(phoneNumbers, read.hideBlocked, read.isBlocked)) return null

    val names = getThreadContactNames(phoneNumbers, read.privateContacts, lookups::name)
    val title = joinGroupNames(names)
    val photoUri = if (phoneNumbers.size == 1) lookups.photoUri(phoneNumbers.first()) else ""
    // Unread means the thread has unread incoming messages. The provider's own threads.read flag is not
    // trusted: some OEM providers (seen on Xiaomi) leave it at 0 after every message is read, and opening the
    // thread can't clear it because there is no unread row left to update. A thread the user marked unread
    // stays unread even with no unread incoming message (e.g. only sent messages).
    val unreadCount = read.unreadMap[id] ?: 0
    return Conversation(
        threadId = id,
        snippet = snippet,
        date = date.toInt(),
        read = unreadCount == 0 && id.toString() !in read.markedUnread,
        title = title,
        photoUri = photoUri,
        isGroupConversation = phoneNumbers.size > 1,
        phoneNumber = phoneNumbers.first(),
        isArchived = read.archiveTrusted && cursor.getIntValue(Threads.ARCHIVED) == 1,
        unreadCount = unreadCount,
    )
}

/** False when the provider returned no cursor at all. */
private fun Context.queryCursorUnsafe(
    uri: Uri,
    projection: Array<String>,
    selection: String? = null,
    selectionArgs: Array<String>? = null,
    sortOrder: String? = null,
    shouldStop: () -> Boolean = { false },
    callback: (cursor: Cursor) -> Unit,
): Boolean {
    val cursor = contentResolver.query(uri, projection, selection, selectionArgs, sortOrder) ?: return false
    cursor.use {
        while (!shouldStop() && cursor.moveToNext()) {
            callback(cursor)
        }
    }
    return true
}

fun Context.getConversationIds(): List<Long> {
    val uri = "${Threads.CONTENT_URI}?simple=true".toUri()
    val projection = arrayOf(Threads._ID)
    val selection = "${Threads.MESSAGE_COUNT} > 0"
    val sortOrder = "${Threads.DATE} ASC"
    val conversationIds = mutableListOf<Long>()
    queryCursor(uri, projection, selection, null, sortOrder, true) { cursor ->
        val id = cursor.getLongValue(Threads._ID)
        conversationIds.add(id)
    }
    return conversationIds
}

// based on https://stackoverflow.com/a/6446831/1967672
@SuppressLint("NewApi")
fun Context.getMmsAttachment(id: Long): MessageAttachment {
    val uri = if (isQPlus()) {
        Mms.Part.CONTENT_URI
    } else {
        "content://mms/part".toUri()
    }

    return getMmsAttachments(listOf(id), uri)[id] ?: MessageAttachment(id, "", arrayListOf())
}

/** One MMS as read from its row, before its parts are attached. */
private class MmsRow(
    val id: Long,
    val type: Int,
    val date: Int,
    val read: Boolean,
    val threadId: Long,
    val subscriptionId: Int,
    val status: Int,
)

/** Parts of many MMS read at once, this many message ids per query (well under SQLite's limits). */
private const val MMS_PARTS_CHUNK = 200

/**
 * The text and attachments of every MMS in [ids], with one parts query per [MMS_PARTS_CHUNK] messages instead of one
 * per message (DL-19). Messages without parts are left out of the map.
 */
@SuppressLint("NewApi")
fun Context.getMmsAttachments(
    ids: Collection<Long>,
    uri: Uri = if (isQPlus()) Mms.Part.CONTENT_URI else "content://mms/part".toUri(),
): Map<Long, MessageAttachment> {
    val projection = arrayOf(
        Mms._ID,
        Mms.Part.MSG_ID,
        Mms.Part.CONTENT_TYPE,
        Mms.Part.TEXT
    )
    val result = HashMap<Long, MessageAttachment>()
    val attachmentNames = HashMap<Long, List<String>?>()
    val attachmentCounts = HashMap<Long, Int>()

    ids.distinct().chunked(MMS_PARTS_CHUNK).forEach { chunk ->
        // The ids are numbers from the provider, so they can go in the selection as they are.
        val selection = "${Mms.Part.MSG_ID} IN (${chunk.joinToString(",")})"
        val sortOrder = "${Mms.Part.MSG_ID} ASC, ${Mms._ID} ASC"
        queryCursor(uri, projection, selection, null, sortOrder, showErrors = true) { cursor ->
            val partId = cursor.getLongValue(Mms._ID)
            val id = cursor.getLongValue(Mms.Part.MSG_ID)
            val mimetype = cursor.getStringValue(Mms.Part.CONTENT_TYPE) ?: ""
            val messageAttachment = result.getOrPut(id) { MessageAttachment(id, "", arrayListOf()) }
            if (mimetype == "text/plain") {
                val part = cursor.getStringValue(Mms.Part.TEXT).orEmpty()
                // Several text slides are joined in order instead of the last one replacing the rest.
                messageAttachment.text = joinMmsText(messageAttachment.text, part)
            } else if (mimetype.startsWith("image/") || mimetype.startsWith("video/")) {
                val fileUri = Uri.withAppendedPath(uri, partId.toString())
                messageAttachment.attachments.add(
                    Attachment(
                        id = partId,
                        messageId = id,
                        uriString = fileUri.toString(),
                        mimetype = mimetype,
                        width = 0,
                        height = 0,
                        filename = ""
                    )
                )
                // The SMIL names list every media part in order, pictures and videos included, so these count too.
                attachmentCounts[id] = (attachmentCounts[id] ?: 0) + 1
            } else if (mimetype != "application/smil") {
                val count = attachmentCounts[id] ?: 0
                val attachmentName = attachmentNames[id]?.getOrNull(count) ?: ""
                val attachment = Attachment(
                    id = partId,
                    messageId = id,
                    uriString = Uri.withAppendedPath(uri, partId.toString()).toString(),
                    mimetype = mimetype,
                    width = 0,
                    height = 0,
                    filename = attachmentName
                )
                messageAttachment.attachments.add(attachment)
                attachmentCounts[id] = count + 1
            } else {
                val text = cursor.getStringValue(Mms.Part.TEXT)
                attachmentNames[id] = try {
                    parseAttachmentNames(text)
                } catch (e: XmlPullParserException) {
                    e.printStackTrace()
                    null
                }
            }
        }
    }

    return result
}

fun Context.getLatestMMS(): Message? {
    val sortOrder = "${Mms.DATE} DESC LIMIT 1"
    return getMMS(sortOrder = sortOrder).firstOrNull()
}

fun Context.getMMS(messageUri: Uri): Message? {
    return getMMS(uri = messageUri).firstOrNull()
}

fun Context.getThreadSnippet(threadId: Long): String {
    val sortOrder = "${Mms.DATE} DESC LIMIT 1"
    val latestMms = getMMS(threadId, sortOrder).firstOrNull()
    var snippet = latestMms?.body ?: ""

    val uri = Sms.CONTENT_URI
    val projection = arrayOf(
        Sms.BODY
    )

    // DL-16: MMS dates are in seconds and SMS dates in milliseconds, so comparing them as they are made every SMS
    // newer than any MMS and an older text hid the newest picture message.
    val selection = "${Sms.THREAD_ID} = ? AND ${Sms.DATE} > ?"
    val selectionArgs = arrayOf(
        threadId.toString(),
        latestMms?.millis()?.toString() ?: "0"
    )
    try {
        val cursor = contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)
        cursor?.use {
            if (cursor.moveToFirst()) {
                snippet = cursor.getStringValue(Sms.BODY)
            }
        }
    } catch (_: Exception) {
    }
    return snippet
}

fun Context.getMessageRecipientAddress(messageId: Long): String {
    val uri = Sms.CONTENT_URI
    val projection = arrayOf(
        Sms.ADDRESS
    )

    val selection = "${Sms._ID} = ?"
    val selectionArgs = arrayOf(messageId.toString())

    try {
        val cursor = contentResolver.query(uri, projection, selection, selectionArgs, null)
        cursor?.use {
            if (cursor.moveToFirst()) {
                return cursor.getStringValue(Sms.ADDRESS)
            }
        }
    } catch (_: Exception) {
    }

    return ""
}

fun Context.getThreadParticipants(
    threadId: Long,
    contactsMap: HashMap<Int, SimpleContact>?,
): ArrayList<SimpleContact> {
    val recipientIds = getThreadRecipientIds(threadId)
    val phoneNumbers = getThreadPhoneNumbers(recipientIds)
    MessagingCache.participantsCache.get(threadId, phoneNumbers)?.let {
        return it
    }

    val participants = ArrayList<SimpleContact>()
    recipientIds.zip(phoneNumbers).forEach { (addressId, number) ->
        val cachedContact = contactsMap?.get(addressId)
        val threadPhoneNumber = cachedContact
            ?.phoneNumbers
            ?.firstOrNull { it.normalizedNumber == number }
        if (cachedContact != null && threadPhoneNumber != null) {
            participants.add(
                cachedContact.copy(
                    phoneNumbers = arrayListOf(threadPhoneNumber.copy()),
                    birthdays = ArrayList(cachedContact.birthdays),
                    anniversaries = ArrayList(cachedContact.anniversaries)
                )
            )
            return@forEach
        }

        val namePhoto = getNameAndPhotoFromPhoneNumber(number)
        val name = namePhoto.name
        val photoUri = namePhoto.photoUri ?: ""
        val phoneNumber = PhoneNumber(number, 0, "", number)
        val contact = SimpleContact(
            rawId = addressId,
            contactId = addressId,
            name = name,
            photoUri = photoUri,
            phoneNumbers = arrayListOf(phoneNumber),
            birthdays = ArrayList(),
            anniversaries = ArrayList()
        )
        participants.add(contact)
    }

    MessagingCache.participantsCache.put(threadId, phoneNumbers, participants)
    return participants
}

/** Text slides of one MMS in order, one per line, capped at [MAX_MESSAGE_LENGTH]. */
internal fun joinMmsText(soFar: String, part: String): String {
    val joined = if (soFar.isEmpty() || part.isEmpty()) soFar + part else soFar + MMS_TEXT_SEPARATOR + part
    return joined.take(MAX_MESSAGE_LENGTH)
}

private const val MMS_TEXT_SEPARATOR = "\n"

private fun Context.getThreadRecipientIds(threadId: Long): List<Int> {
    val recipientIds = ArrayList<Int>()
    queryCursor(
        uri = "${MmsSms.CONTENT_CONVERSATIONS_URI}?simple=true".toUri(),
        projection = arrayOf(ThreadsColumns.RECIPIENT_IDS),
        selection = "${Mms._ID} = ?",
        selectionArgs = arrayOf(threadId.toString()),
        showErrors = true
    ) { cursor ->
        cursor.getStringValue(ThreadsColumns.RECIPIENT_IDS)
            .split(" ")
            .filter { it.areDigitsOnly() }
            .forEach { recipientIds.add(it.toInt()) }
    }

    return recipientIds
}

fun Context.getThreadPhoneNumbers(recipientIds: List<Int>): ArrayList<String> {
    val numbers = ArrayList<String>()
    recipientIds.forEach {
        numbers.add(getPhoneNumberFromAddressId(it))
    }
    return numbers
}

fun Context.getThreadContactNames(
    phoneNumbers: List<String>,
    privateContacts: ArrayList<SimpleContact>,
    nameOf: (String) -> String = SimpleContactsHelper(this)::getNameFromPhoneNumber,
): ArrayList<String> {
    val names = ArrayList<String>()
    phoneNumbers.forEach { number ->
        val name = nameOf(number)
        if (name != number) {
            names.add(name)
        } else {
            val privateContact = privateContacts.firstOrNull { it.doesHavePhoneNumber(number) }
            if (privateContact == null) {
                names.add(name)
            } else {
                names.add(privateContact.name)
            }
        }
    }
    // A thread created from a broken link can carry a NUL in its address; never show it in a title.
    return names.mapTo(ArrayList()) { it.stripControlCharacters() }
}

fun Context.getPhoneNumberFromAddressId(canonicalAddressId: Int): String {
    val uri = Uri.withAppendedPath(MmsSms.CONTENT_URI, "canonical-addresses")
    val projection = arrayOf(
        Mms.Addr.ADDRESS
    )

    val selection = "${Mms._ID} = ?"
    val selectionArgs = arrayOf(canonicalAddressId.toString())
    try {
        val cursor = contentResolver.query(uri, projection, selection, selectionArgs, null)
        cursor?.use {
            if (cursor.moveToFirst()) {
                return cursor.getStringValue(Mms.Addr.ADDRESS)
            }
        }
    } catch (e: Exception) {
        showErrorToast(e)
    }
    return ""
}

fun Context.getSuggestedContacts(
    privateContacts: ArrayList<SimpleContact>,
): ArrayList<SimpleContact> {
    val contacts = ArrayList<SimpleContact>()
    val region = defaultRegion(this)
    val normalizer = NumberNormalizer { region }
    val uri = Sms.CONTENT_URI
    val projection = arrayOf(
        Sms.ADDRESS,
        Sms.THREAD_ID
    )

    val sortOrder = "${Sms.DATE} DESC LIMIT 50"
    val blockedNumbers = getBlockedNumbers()
    // People from chats hidden behind the app lock must not show up here while it is locked.
    val hiddenThreadIds = com.glooboo.umessage.helpers.HiddenChats.get(this).hiddenIds()

    queryCursor(uri, projection, null, null, sortOrder, showErrors = true) { cursor ->
        if (cursor.getLongValue(Sms.THREAD_ID) in hiddenThreadIds) return@queryCursor
        val senderNumber = cursor.getStringValue(Sms.ADDRESS) ?: return@queryCursor
        val namePhoto = getNameAndPhotoFromPhoneNumber(senderNumber)
        var senderName = namePhoto.name
        var photoUri = namePhoto.photoUri ?: ""
        if (isNumberBlocked(senderNumber, blockedNumbers)) {
            return@queryCursor
        } else if (namePhoto.name == senderNumber) {
            if (privateContacts.isNotEmpty()) {
                val privateContact = privateContacts.firstOrNull {
                    it.phoneNumbers.first().normalizedNumber == senderNumber
                }
                if (privateContact != null) {
                    senderName = privateContact.name
                    photoUri = privateContact.photoUri
                } else {
                    return@queryCursor
                }
            } else {
                return@queryCursor
            }
        }

        val phoneNumber = PhoneNumber(senderNumber, 0, "", senderNumber)
        val contact = SimpleContact(
            rawId = 0,
            contactId = 0,
            name = senderName,
            photoUri = photoUri,
            phoneNumbers = arrayListOf(phoneNumber),
            birthdays = ArrayList(),
            anniversaries = ArrayList()
        )
        // Two senders are one suggestion only when they are the same number, not merely the same last 9 digits.
        if (contacts.none { normalizer.sameAddress(it.phoneNumbers.first().normalizedNumber, senderNumber) }) {
            contacts.add(contact)
        }
    }

    return contacts
}

/** True when [number] belongs to a saved contact. False without the contacts permission. */
fun Context.isSavedContactNumber(number: String): Boolean {
    if (number.isEmpty() || !hasPermission(PERMISSION_READ_CONTACTS)) return false
    val uri = Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
    return try {
        contentResolver.query(uri, arrayOf(PhoneLookup._ID), null, null, null)?.use { it.moveToFirst() } == true
    } catch (_: Exception) {
        false
    }
}

fun Context.getNameAndPhotoFromPhoneNumber(number: String): NamePhoto {
    MessagingCache.namePhoto.get(number)?.let { return it }
    if (!hasPermission(PERMISSION_READ_CONTACTS)) {
        return NamePhoto(number, null)
    }

    val uri = Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
    val projection = arrayOf(
        PhoneLookup.DISPLAY_NAME,
        PhoneLookup.PHOTO_URI
    )

    val result = try {
        val cursor = contentResolver.query(uri, projection, null, null, null)
        cursor.use {
            if (cursor?.moveToFirst() == true) {
                val name = cursor.getStringValue(PhoneLookup.DISPLAY_NAME)
                val photoUri = cursor.getStringValue(PhoneLookup.PHOTO_URI)
                NamePhoto(name, photoUri)
            } else {
                NamePhoto(number, null)
            }
        }
    } catch (_: Exception) {
        NamePhoto(number, null)
    }

    MessagingCache.namePhoto.put(number, result)
    return result
}

fun Context.insertNewSMS(
    address: String,
    subject: String,
    body: String,
    date: Long,
    read: Int,
    threadId: Long,
    type: Int,
    subscriptionId: Int,
    dateSent: Long = 0L,
    /** Provider truth (3.4): a text stored in Spam is written read and seen (1). Null leaves the provider's default. */
    seen: Int? = null,
): Long {
    val uri = Sms.CONTENT_URI
    val contentValues = ContentValues().apply {
        put(Sms.ADDRESS, address)
        put(Sms.SUBJECT, subject)
        put(Sms.BODY, body)
        put(Sms.DATE, date)
        // The carrier's send time: the caller's [dateSent] (0 = unknown), else the receiver's own (I16). With neither,
        // the provider keeps its own default.
        (dateSent.takeIf { it > 0L } ?: IncomingDateSent.current())?.let { put(Sms.DATE_SENT, it) }
        put(Sms.READ, read)
        seen?.let { put(Sms.SEEN, it) }
        put(Sms.THREAD_ID, threadId)
        put(Sms.TYPE, type)
        put(Sms.SUBSCRIPTION_ID, subscriptionId)
    }

    return try {
        val newUri = contentResolver.insert(uri, contentValues)
        newUri?.lastPathSegment?.toLong() ?: 0L
    } catch (_: Exception) {
        0L
    }
}

fun Context.removeAllArchivedConversations(callback: (() -> Unit)? = null) {
    ensureBackgroundThread {
        try {
            if (ProviderTruthFlag.enabled) {
                // B-1: the Archived list only (not the Spam chats), the role checked once, stopped at the first
                // chat that is not deleted; "emptied" only when every one was.
                val isDefault = isDefaultSmsAppNow()
                val result = conversationsDB.getArchivedList().asSequence()
                    .map { deleteConversation(it.threadId, isDefault) }
                    .firstOrNull { it != DeleteResult.DELETED } ?: DeleteResult.DELETED
                toast(result.messageRes ?: R.string.archive_emptied_successfully)
            } else {
                for (conversation in conversationsDB.getAllArchived()) {
                    deleteConversation(conversation.threadId)
                }
                toast(R.string.archive_emptied_successfully)
            }
            callback?.invoke()
        } catch (_: Exception) {
            toast(org.fossify.commons.R.string.unknown_error_occurred)
        }
    }
}

/**
 * What a delete did (S14, B-13). The delete functions never show a message themselves under provider truth; the
 * screen that asked shows [messageRes].
 */
enum class DeleteResult {
    DELETED,

    /** uMessage is not the default SMS app: the phone's store keeps everything, and so does the app. */
    NOT_DEFAULT,

    /** The store still holds the messages after the delete: nothing of the app's own data was removed. */
    NOTHING_DELETED,

    /** The store refused or failed. */
    FAILED;

    /** The message for the user; null when the delete worked. */
    val messageRes: Int?
        get() = when (this) {
            DELETED -> null
            NOT_DEFAULT -> R.string.pt_delete_not_default
            NOTHING_DELETED -> R.string.pt_delete_nothing
            FAILED -> org.fossify.commons.R.string.unknown_error_occurred
        }
}

/**
 * Deletes the chat [threadId] from the phone's store, then everything the app keeps of it ([forgetThread]).
 *
 * S14, B-13 (provider truth): the store is the only copy of the messages, so the app's own data of the chat (notes,
 * labels, draft, cache) goes only once the messages are gone. When uMessage is not the default SMS app (the store then
 * ignores the delete), or the store still holds messages of the chat afterwards, nothing is removed and the result
 * says why; no message is shown here, the screen that asked shows it. [isDefault]: the role, checked once by a caller
 * that deletes many chats. A chat that holds only scheduled messages is not in the store: it is forgotten at once.
 */
fun Context.deleteConversation(threadId: Long, isDefault: Boolean? = null): DeleteResult {
    val honest = ProviderTruthFlag.enabled
    val result = when {
        honest && isScheduledOnly(threadId) -> DeleteResult.DELETED
        honest && !(isDefault ?: isDefaultSmsAppNow()) -> DeleteResult.NOT_DEFAULT
        !deleteThreadFromStore(threadId, quiet = honest) -> DeleteResult.FAILED
        honest && storeHasThreadMessages(threadId) != false -> DeleteResult.NOTHING_DELETED
        else -> DeleteResult.DELETED
    }
    if (result == DeleteResult.DELETED) forgetThread(threadId)
    return result
}

/** A chat the app made for scheduled messages only (its id is made up, or its row says so): not in the store. */
private fun Context.isScheduledOnly(threadId: Long): Boolean = threadId >= SCHEDULED_ID_MIN ||
    runCatching { conversationsDB.getConversationWithThreadId(threadId)?.isScheduled == true }.getOrDefault(false)

/** The SMS, then the MMS of [threadId] in the phone's store; false when a delete failed. [quiet]: no error toast. */
private fun Context.deleteThreadFromStore(threadId: Long, quiet: Boolean): Boolean {
    var uri = Sms.CONTENT_URI
    val selection = "${Sms.THREAD_ID} = ?"
    val selectionArgs = arrayOf(threadId.toString())
    try {
        contentResolver.delete(uri, selection, selectionArgs)
    } catch (e: Exception) {
        if (!quiet) showErrorToast(e)
        // The messages are still in the provider: wiping the local copy would only bring them back later.
        return false
    }

    uri = Mms.CONTENT_URI
    try {
        contentResolver.delete(uri, selection, selectionArgs)
    } catch (e: Exception) {
        e.printStackTrace()
        return false
    }
    return true
}

/**
 * S19: removes everything the app keeps of the chat [threadId]: its cached row and messages, labels, notes, reminders
 * (and their alarms), draft, mute and hidden marks, its own notification channel, its shortcut and its spam_threads
 * row. [vanished]: the thread left the phone's store without the app (another app deleted it); its scheduled messages
 * stay, as this app holds their only copy.
 */
fun Context.forgetThread(threadId: Long, vanished: Boolean = false) {
    conversationsDB.deleteThreadId(threadId)
    runCatching { conversationsDB.deleteSpamThread(threadId) }
    kotlinx.coroutines.runBlocking { labelsDB.removeAllFromConversation(threadId) }
    kotlinx.coroutines.runBlocking { messageNotes.onThreadDeleted(threadId) }
    if (vanished) messagesDB.deleteVanishedThreadMessages(threadId) else messagesDB.deleteThreadMessages(threadId)
    MessagingCache.participantsCache.remove(threadId)
    // Per-conversation leftovers: reminders (and their alarms), the draft, the mute and the hidden mark.
    cancelRemindersForThread(threadId)
    runCatching { draftsDB.delete(threadId) }
    runCatching { com.glooboo.umessage.helpers.MutePrefs.get(this).unmute(threadId) }
    runCatching { com.glooboo.umessage.helpers.HiddenChats.get(this).unhide(threadId) }

    if (config.customNotifications.contains(threadId.toString())) {
        config.removeCustomNotificationsByThreadId(threadId)
        notificationManager.deleteNotificationChannel(threadId.toString())
    }
    if(shortcutHelper.getShortcut(threadId) != null) {
        shortcutHelper.removeShortcutForThread(threadId)
    }
}

/** S14: whether uMessage holds the default SMS role now; false when it cannot be told. */
private fun Context.isDefaultSmsAppNow(): Boolean =
    runCatching { DefaultSmsRole.status(this) == DefaultSmsRole.Status.Held }.getOrDefault(false)

/** S14: whether the phone's store still has an SMS or MMS of [threadId]; null when it could not be read. */
private fun Context.storeHasThreadMessages(threadId: Long): Boolean? = runCatching {
    listOf(Sms.CONTENT_URI, Mms.CONTENT_URI).any { uri ->
        contentResolver.query(uri, arrayOf(Sms._ID), "${Sms.THREAD_ID} = ?", arrayOf(threadId.toString()), null)
            ?.use { it.count > 0 } ?: error("no cursor")
    }
}.getOrNull()

/** S14: whether the row [id] of [uri] is still in the phone's store; null when it could not be read. */
private fun Context.storeHasRow(uri: Uri, id: Long): Boolean? = runCatching {
    contentResolver.query(uri, arrayOf(Sms._ID), "${Sms._ID} = ?", arrayOf(id.toString()), null)
        ?.use { it.count > 0 } ?: error("no cursor")
}.getOrNull()

fun Context.checkAndDeleteOldRecycleBinMessages(callback: (() -> Unit)? = null) {
    if (
        config.useRecycleBin
        && config.lastRecycleBinCheck < System.currentTimeMillis() - DAY_SECONDS * 1000
    ) {
        config.lastRecycleBinCheck = System.currentTimeMillis()
        ensureBackgroundThread {
            try {
                deleteOldRecycleBinMessages(System.currentTimeMillis())
                callback?.invoke()
            } catch (_: Exception) {
            }
        }
    }
}

/** One SMS or MMS read from the provider, or null when it is gone or cannot be read. */
private fun Context.readProviderMessage(id: Long, isMMS: Boolean): Message? = try {
    if (isMMS) {
        getMMS(Uri.withAppendedPath(Mms.CONTENT_URI, id.toString()))
    } else {
        var threadId = -1L
        var dateSec = -1
        queryCursor(
            Sms.CONTENT_URI, arrayOf(Sms.THREAD_ID, Sms.DATE), "${Sms._ID} = ?", arrayOf(id.toString())
        ) { cursor ->
            threadId = cursor.getLongValue(Sms.THREAD_ID)
            dateSec = (cursor.getLongValue(Sms.DATE) / 1000).toInt()
        }
        if (threadId == -1L) {
            null
        } else {
            getMessages(threadId, dateSec, includeScheduledMessages = false, includeSameSecond = true)
                .firstOrNull { it.key == MessageKey(id, false) }
        }
    }
} catch (e: Exception) {
    null
}

/** Removes for good every recycle-bin message deleted more than a month before [nowMillis]. */
fun Context.deleteOldRecycleBinMessages(nowMillis: Long) {
    val entries = messagesDB.getOldRecycleBinEntries(nowMillis - MONTH_SECONDS * 1000L)
    val threads = entries.mapNotNullTo(HashSet()) { messagesDB.getMessage(it.id, it.isMMS)?.threadId }
    val result = purgeRecycleBinEntries(entries)
    // S19, B-13: a chat the bin emptied is forgotten as a deleted chat (the screens tidy their own purges). B2-9: only
    // when the purge deleted; a purge that stopped (not the default app) leaves every chat as it is.
    if (ProviderTruthFlag.enabled && result == DeleteResult.DELETED) {
        threads.forEach { runCatching { tidyConversationAfterBinPurge(it) } }
    }
}

/** Empties the whole bin; with the flag on it stops at the first message the store did not delete. */
fun Context.emptyMessagesRecycleBin(): DeleteResult = purgeRecycleBinEntries(messagesDB.getAllRecycleBinEntries())

/**
 * DL-13: works from the bin entries themselves, so no entry is skipped. An entry with a cached copy is a message
 * the bin shows: it is deleted from the phone. An entry without one (left by older versions, which could bin a
 * message that was never cached) is only dropped: its message never left the chat, and for entries carried over from
 * before messages were keyed by type, whether it was an SMS or an MMS is a guess, so deleting by it could remove
 * a different message.
 */
private fun Context.purgeRecycleBinEntries(entries: List<RecycleBinMessage>): DeleteResult {
    // B-13: the role is checked once for the batch; the first message not deleted stops it.
    val isDefault = if (ProviderTruthFlag.enabled) isDefaultSmsAppNow() else null
    for (entry in entries) {
        if (messagesDB.getMessage(entry.id, entry.isMMS) != null) {
            val result = deleteMessage(entry.id, entry.isMMS, isDefault)
            if (ProviderTruthFlag.enabled && result != DeleteResult.DELETED) return result
        } else {
            messagesDB.deleteFromRecycleBin(entry.id, entry.isMMS)
        }
    }
    return DeleteResult.DELETED
}

fun Context.emptyMessagesRecycleBinForConversation(threadId: Long): DeleteResult {
    val messages = messagesDB.getThreadMessagesFromRecycleBin(threadId)
    val isDefault = if (ProviderTruthFlag.enabled) isDefaultSmsAppNow() else null
    for (message in messages) {
        val result = deleteMessage(message.id, message.isMMS, isDefault)
        if (ProviderTruthFlag.enabled && result != DeleteResult.DELETED) return result
    }
    return DeleteResult.DELETED
}

fun Context.restoreAllMessagesFromRecycleBinForConversation(threadId: Long) {
    messagesDB.deleteThreadMessagesFromRecycleBin(threadId)
}

/**
 * Moves a message to the recycle bin. The bin lists, restores and empties messages through their cached copies,
 * so one is made first when the message was never cached (DL-13: an older message paged in, or one deleted from
 * its notification); [message] is that copy when the caller has it, otherwise it is read from the provider.
 */
fun Context.moveMessageToRecycleBin(id: Long, isMMS: Boolean, message: Message? = null) {
    try {
        if (messagesDB.getMessage(id, isMMS) == null) {
            val copy = message?.takeIf { it.key == MessageKey(id, isMMS) } ?: readProviderMessage(id, isMMS)
            copy?.let { messagesDB.insertOrIgnore(it) }
        }
        messagesDB.insertRecycleBinEntry(RecycleBinMessage(id, isMMS, System.currentTimeMillis()))
    } catch (e: Exception) {
        showErrorToast(e)
    }
}

fun Context.restoreMessageFromRecycleBin(id: Long, isMMS: Boolean) {
    try {
        messagesDB.deleteFromRecycleBin(id, isMMS)
    } catch (e: Exception) {
        showErrorToast(e)
    }
}

fun Context.updateConversationArchivedStatus(threadId: Long, archived: Boolean) {
    val uri = Threads.CONTENT_URI
    val values = ContentValues().apply {
        put(Threads.ARCHIVED, archived)
    }
    val selection = "${Threads._ID} = ?"
    val selectionArgs = arrayOf(threadId.toString())
    try {
        contentResolver.update(uri, values, selection, selectionArgs)
        // The store may take the write and not keep it: then the app's own list below is the one to trust.
        ArchiveStore.verifyWrite(this, threadId, archived)
    } catch (sqliteException: SQLiteException) {
        if (
            sqliteException.message?.contains("no such column: archived") == true
            && config.isArchiveAvailable
        ) {
            // The phone's store has no archive flag: keep the chat archived in the app's own list instead.
            config.isArchiveAvailable = false
        } else {
            throw sqliteException
        }
    }
    if (archived) {
        conversationsDB.moveToArchive(threadId)
    } else {
        conversationsDB.unarchive(threadId)
    }
}

/**
 * Deletes one message from the phone's store, then the app's copy, reminders and note. S14, B-13 (provider truth):
 * not the default SMS app, or the row still in the store after the delete: nothing of the app's own data is removed
 * and the result says why (no message here; the screen that asked shows it). A row that was already gone counts as
 * deleted (the copy is stale). [isDefault]: the role, checked once by a caller that deletes many.
 */
fun Context.deleteMessage(id: Long, isMMS: Boolean, isDefault: Boolean? = null): DeleteResult {
    val honest = ProviderTruthFlag.enabled
    if (honest && !(isDefault ?: isDefaultSmsAppNow())) return DeleteResult.NOT_DEFAULT
    val uri = if (isMMS) Mms.CONTENT_URI else Sms.CONTENT_URI
    val selection = "${Sms._ID} = ?"
    val selectionArgs = arrayOf(id.toString())
    // The note key is built from the cached copy, so read it before the copy goes. A message that was never cached
    // keeps its note (it is only dropped with the chat): nothing here can tell which note it had.
    val noteKey = runCatching { messagesDB.getMessage(id, isMMS)?.let { MessageNoteKey.of(it) } }.getOrNull()
    return try {
        val deleted = contentResolver.delete(uri, selection, selectionArgs)
        if (honest && deleted == 0 && storeHasRow(uri, id) != false) return DeleteResult.NOTHING_DELETED
        messagesDB.delete(id, isMMS)
        cancelRemindersForMessage(MessageKey(id, isMMS))
        noteKey?.let { kotlinx.coroutines.runBlocking { messageNotes.onMessageDeleted(it) } }
        DeleteResult.DELETED
    } catch (e: Exception) {
        if (!honest) showErrorToast(e)
        DeleteResult.FAILED
    }
}

fun Context.deleteScheduledMessage(messageId: Long) {
    try {
        messagesDB.deleteScheduledMessage(messageId)
    } catch (e: Exception) {
        showErrorToast(e)
    }
}

fun Context.markMessageRead(id: Long, isMMS: Boolean) {
    val uri = if (isMMS) Mms.CONTENT_URI else Sms.CONTENT_URI
    val contentValues = ContentValues().apply {
        put(Sms.READ, 1)
        put(Sms.SEEN, 1)
    }
    val selection = "${Sms._ID} = ?"
    val selectionArgs = arrayOf(id.toString())
    contentResolver.update(uri, contentValues, selection, selectionArgs)
    messagesDB.markRead(id, isMMS)
}

fun Context.markThreadMessagesRead(threadId: Long) {
    val id = threadId.toString()

    val smsValues = ContentValues().apply {
        put(Sms.READ, 1)
        put(Sms.SEEN, 1)
    }
    val smsSelection = "${Sms.THREAD_ID}=? AND ${Sms.TYPE}=? AND (${Sms.READ}=? OR ${Sms.SEEN}=?)"
    val smsArgs = arrayOf(id, Sms.MESSAGE_TYPE_INBOX.toString(), "0", "0")
    contentResolver.update(Sms.CONTENT_URI, smsValues, smsSelection, smsArgs)

    val mmsValues = ContentValues().apply {
        put(Mms.READ, 1)
        put(Mms.SEEN, 1)
    }
    val mmsSelection = "${Mms.THREAD_ID}=? AND ${Mms.MESSAGE_BOX}=? AND (${Mms.READ}=? OR ${Mms.SEEN}=?)"
    val mmsArgs = arrayOf(id, Mms.MESSAGE_BOX_INBOX.toString(), "0", "0")
    contentResolver.update(Mms.CONTENT_URI, mmsValues, mmsSelection, mmsArgs)

    messagesDB.markThreadRead(threadId)
    conversationsDB.markRead(threadId)
    config.setThreadMarkedUnread(threadId, false)
}

/**
 * Marks the thread unread the way Google Messages does: only the newest incoming message becomes unread, so the
 * badge shows 1, not the whole history. A thread with no incoming message is kept unread by the Config flag alone,
 * which shows it unread without a number.
 */
fun Context.markThreadMessagesUnread(threadId: Long) {
    val id = threadId.toString()
    // Id and date of the newest row; only the first row of the cursor is read.
    fun newest(uri: Uri, selection: String): Pair<Long, Long>? = try {
        contentResolver.query(uri, arrayOf(Sms._ID, Sms.DATE), selection, arrayOf(id), "${Sms.DATE} DESC")?.use {
            if (it.moveToFirst()) it.getLongValue(Sms._ID) to it.getLongValue(Sms.DATE) else null
        }
    } catch (_: Exception) {
        null
    }

    val sms = newest(Sms.CONTENT_URI, "${Sms.THREAD_ID}=? AND ${Sms.TYPE}=${Sms.MESSAGE_TYPE_INBOX}")
    // MMS dates are seconds, SMS dates milliseconds.
    val mms = newest(Mms.CONTENT_URI, "${Mms.THREAD_ID}=? AND ${Mms.MESSAGE_BOX}=${Mms.MESSAGE_BOX_INBOX}")
        ?.let { it.first to it.second * 1000 }
    val target = when {
        sms == null && mms == null -> null
        mms == null || (sms != null && sms.second >= mms.second) -> Sms.CONTENT_URI to sms!!.first
        else -> Mms.CONTENT_URI to mms.first
    }
    if (target != null) {
        val contentValues = ContentValues().apply {
            put(Sms.READ, 0)
            put(Sms.SEEN, 0)
        }
        contentResolver.update(target.first, contentValues, "${Sms._ID} = ?", arrayOf(target.second.toString()))
    }
    conversationsDB.markUnread(threadId)
    config.setThreadMarkedUnread(threadId, true)
}

@SuppressLint("NewApi")
fun Context.getThreadId(address: String): Long {
    return try {
        Threads.getOrCreateThreadId(this, address)
    } catch (_: Exception) {
        0L
    }
}

@SuppressLint("NewApi")
fun Context.getThreadId(addresses: Set<String>): Long {
    return try {
        Threads.getOrCreateThreadId(this, addresses)
    } catch (_: Exception) {
        0L
    }
}

fun Context.showReceivedMessageNotification(
    messageId: Long,
    isMms: Boolean,
    address: String,
    senderName: String,
    body: String,
    threadId: Long,
    bitmap: Bitmap?,
    subscriptionId: Int = SubscriptionManager.INVALID_SUBSCRIPTION_ID,
    notify: NotifyContext = NotifyContext(),
) {
    // The chat may have been opened and marked read between the delivery and this post; a notification posted
    // now would outlive the cancel that came first and keep the chat looking unread. The database is read off the
    // main thread, where Room refuses queries.
    // The whole post (smart replies, avatar, shortcut, notify) runs on this background thread; none of it needs main.
    ensureBackgroundThread {
        postReceivedMessageNotification(
            messageId, isMms, address, senderName, body, threadId, bitmap, subscriptionId, notify,
        )
    }
}

/** The same, blocking, with the outcome. Call it off the main thread. Never throws. */
internal fun Context.postReceivedMessageNotification(
    messageId: Long,
    isMms: Boolean,
    address: String,
    senderName: String,
    body: String,
    threadId: Long,
    bitmap: Bitmap?,
    subscriptionId: Int = SubscriptionManager.INVALID_SUBSCRIPTION_ID,
    notify: NotifyContext = NotifyContext(),
): NotifyResult {
    if (isMessageSavedAsRead(messageId, isMms)) {
        runCatching { notificationManager.cancel(threadId.hashCode()) }
        return NotifyLog.record(null, threadId, NotifyResult.SKIPPED_READ)
    }
    return runCatching {
        notificationHelper.postMessageNotification(
            messageId, isMms, address, body, threadId, bitmap, senderName, subscriptionId, notify,
        )
    }.getOrElse { NotifyLog.record(null, threadId, NotifyResult.FAILED, it) }
}

/** True when the saved copy of the message is already read (the chat was opened meanwhile). */
internal fun Context.isMessageSavedAsRead(messageId: Long, isMms: Boolean): Boolean =
    runCatching { messagesDB.getMessage(messageId, isMms)?.read == true }.getOrDefault(false)

fun Context.getNameFromAddress(address: String, privateCursor: Cursor?): String {
    var sender = getNameAndPhotoFromPhoneNumber(address).name
    if (address == sender) {
        val privateContacts = MyContactsContentProvider.getSimpleContacts(this, privateCursor)
        sender = privateContacts.firstOrNull { it.doesHavePhoneNumber(address) }?.name ?: address
    }
    return sender
}

fun Context.getContactFromAddress(address: String, callback: ((contact: SimpleContact?) -> Unit)) {
    val privateCursor = getMyContactsCursor(false, true)
    SimpleContactsHelper(this).getAvailableContacts(false) {
        // Closed on both paths; getSimpleContacts only closes it when it reads it.
        val contact = privateCursor.use { cursor ->
            it.firstOrNull { it.doesHavePhoneNumber(address) }
                ?: MyContactsContentProvider.getSimpleContacts(this, cursor)
                    .firstOrNull { it.doesHavePhoneNumber(address) }
        }
        callback(contact)
    }
}

fun Context.getNotificationBitmap(photoUri: String): Bitmap? {
    val size = resources.getDimension(R.dimen.notification_large_icon_size).toInt()
    if (photoUri.isEmpty()) {
        return null
    }

    val options = RequestOptions()
        .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
        .centerCrop()

    return try {
        Glide.with(this)
            .asBitmap()
            .load(photoUri)
            .apply(options)
            .apply(RequestOptions.circleCropTransform())
            .into(size, size)
            .get()
    } catch (_: Exception) {
        null
    }
}

fun Context.removeDiacriticsIfNeeded(text: String): String {
    return if (config.useSimpleCharacters) text.normalizeString() else text
}

fun Context.getSmsDraft(threadId: Long): String {
    val draft = try {
        draftsDB.getDraftById(threadId)
    } catch (_: Exception) {
        null
    }

    return draft?.body.orEmpty()
}

fun Context.getAllDrafts(): HashMap<Long, String> {
    val drafts = HashMap<Long, String>()
    try {
        draftsDB.getAll().forEach {
            drafts[it.threadId] = it.body
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }

    return drafts
}

fun Context.saveSmsDraft(body: String, threadId: Long) {
    val draft = Draft(
        threadId = threadId,
        body = body,
        date = System.currentTimeMillis()
    )

    try {
        draftsDB.insertOrUpdate(draft)
    } catch (e: Exception) {
        e.printStackTrace()
        showErrorToast(e)
    }
}

fun Context.deleteSmsDraft(threadId: Long) {
    try {
        draftsDB.delete(threadId)
    } catch (e: Exception) {
        e.printStackTrace()
        showErrorToast(e)
    }
}

fun Context.updateLastConversationMessage(threadId: Long) {
    updateLastConversationMessage(setOf(threadId))
}

/**
 * Makes the provider date [threadId] by its newest message again. The provider stamps a thread with the time of
 * each insert, not the message's own date, so a message stored later than it arrived (a "Not spam" restore, a
 * replayed or slowly filtered SMS) left the thread at the insert time until something else recomputed it: the chat
 * list showed one time and then, after the next delete anywhere, an earlier one. An always-false delete on the
 * thread's URI runs the provider's own recompute without deleting anything.
 */
fun Context.recomputeThreadDate(threadId: Long) {
    try {
        contentResolver.delete(ContentUris.withAppendedId(Threads.CONTENT_URI, threadId), "1 = 0", null)
    } catch (_: Exception) {
    }
}

/** True when a message dated [dateMillis] is stored noticeably later than it arrived (see [recomputeThreadDate]). */
fun isStoredLate(dateMillis: Long, nowMillis: Long = System.currentTimeMillis()): Boolean =
    nowMillis - dateMillis > STORED_LATE_MS

private const val STORED_LATE_MS = 1_000L

fun Context.updateLastConversationMessage(threadIds: Iterable<Long>) {
    // update the date and the snippet of the threads, by triggering the
    // following Android code (which runs even if no messages are deleted):
    // https://android.googlesource.com/platform/packages/providers/TelephonyProvider/+/android14-release/src/com/android/providers/telephony/MmsSmsProvider.java#1409
    val uri = Threads.CONTENT_URI
    val selection =
        "1 = 0" // always-false condition, because we don't actually want to delete any messages
    try {
        contentResolver.delete(uri, selection, null)
        for (threadId in threadIds) {
            // One thread without a row (an empty thread, or one of a blocked number) must not stop the others from
            // being refreshed (B12).
            runCatching {
                getConversations(threadId).firstOrNull()?.let { insertOrUpdateConversation(it) }
            }
        }
    } catch (_: Exception) {
    }
}

fun Context.getFileSizeFromUri(uri: Uri): Long {
    val assetFileDescriptor = try {
        contentResolver.openAssetFileDescriptor(uri, "r")
    } catch (_: FileNotFoundException) {
        null
    }

    // uses ParcelFileDescriptor#getStatSize underneath if failed
    val length = assetFileDescriptor?.use { it.length } ?: FILE_SIZE_NONE
    if (length != -1L) {
        return length
    }

    // if "content://" uri scheme, try contentResolver table
    if (uri.scheme.equals(ContentResolver.SCHEME_CONTENT)) {
        return contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                // maybe shouldn't trust ContentResolver for size:
                // https://stackoverflow.com/questions/48302972/content-resolver-returns-wrong-size
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex == -1) {
                    return@use FILE_SIZE_NONE
                }
                cursor.moveToFirst()
                return try {
                    cursor.getLong(sizeIndex)
                } catch (_: Throwable) {
                    FILE_SIZE_NONE
                }
            } ?: FILE_SIZE_NONE
    } else {
        return FILE_SIZE_NONE
    }
}

// fix a glitch at enabling Release version minifying from 5.12.3
// reset messages in 5.14.3 again, as PhoneNumber is no longer minified
// reset messages in 5.19.1 again, as SimpleContact is no longer minified
fun Context.clearAllMessagesIfNeeded(callback: () -> Unit) {
    if (!config.wasDbCleared) {
        ensureBackgroundThread {
            // Only the cache: scheduled messages and the recycle bin exist nowhere else.
            messagesDB.deleteCachedMessages()
            MessageCacheImport.restart(this)
            config.wasDbCleared = true
            Handler(Looper.getMainLooper()).post(callback)
        }
    } else {
        callback()
    }
}

fun Context.subscriptionManagerCompat(): SubscriptionManager {
    return getSystemService(SubscriptionManager::class.java)
}

fun Context.insertOrUpdateConversation(
    conversation: Conversation,
    cachedConv: Conversation? = conversationsDB.getConversationWithThreadId(conversation.threadId),
) {
    var updatedConv = conversation
    if (cachedConv != null && cachedConv.usesCustomTitle) {
        updatedConv = updatedConv.copy(
            title = cachedConv.title,
            usesCustomTitle = true
        )
    }
    if (!ArchiveStore.isTrusted(this)) {
        // The store does not keep the archived flag: it reads "not archived" and must not undo the app's own. The
        // snapshot may be older than an archive the user just made, so the flag is read again right before the write.
        val current = conversationsDB.getConversationWithThreadId(conversation.threadId) ?: cachedConv
        if (current != null) updatedConv = updatedConv.copy(isArchived = current.isArchived)
    }
    val isNew = ProviderTruthFlag.enabled && cachedConv == null &&
        conversationsDB.getConversationWithThreadId(updatedConv.threadId) == null
    if (isNew) {
        // B-6: a chat cached for the first time: its sender's history, then the row and its sort in one transaction.
        NewChatSorting.cacheAndSort(this, listOf(updatedConv))
        followSpamRow(updatedConv, renumbered = false)
        return
    }
    conversationsDB.insertOrUpdate(updatedConv)
    val renumbered = cachedConv != null && cachedConv.phoneNumber != updatedConv.phoneNumber
    if (ProviderTruthFlag.enabled) followSpamRow(updatedConv, renumbered)
}

/**
 * Provider truth (S9): a Spam row written for a new text names the sender as the text had it; the cached chat may
 * write the same number another way. The row then follows the chat's number at once (its unread count and spam_since
 * stay), so the chat is in Spam as soon as its row is cached. B-21: a cached chat whose number changed is sorted
 * again.
 */
private fun Context.followSpamRow(conversation: Conversation, renumbered: Boolean) {
    runCatching {
        val row = conversationsDB.getSpamThread(conversation.threadId)
        val number = conversation.phoneNumber
        if (row != null && row.sender != number && !conversation.isGroupConversation) {
            if (SpamThreads.readers(this).same(row.sender, number)) {
                conversationsDB.updateSpamThreadSender(row.threadId, number, row.cause)
            }
        }
    }
    if (renumbered) SpamSorting.requestThreads(this, listOf(conversation.threadId))
}

fun Context.renameConversation(conversation: Conversation, newTitle: String): Conversation {
    val updatedConv = conversation.copy(title = newTitle, usesCustomTitle = true)
    try {
        conversationsDB.updateTitle(conversation.threadId, newTitle, usesCustomTitle = true)
        ensureBackgroundThread {
            shortcutHelper.createOrUpdateShortcut(updatedConv)
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return updatedConv
}

fun Context.createTemporaryThread(
    message: Message,
    threadId: Long = generateRandomId(),
    cachedConv: Conversation?,
) {
    val simpleContactHelper = SimpleContactsHelper(this)
    val addresses = message.participants.getAddresses()
    val photoUri = if (addresses.size == 1) {
        simpleContactHelper.getPhotoUriFromPhoneNumber(addresses.first())
    } else {
        ""
    }

    val title = if (cachedConv != null && cachedConv.usesCustomTitle) {
        cachedConv.title
    } else {
        message.participants.getThreadTitle()
    }

    val conversation = Conversation(
        threadId = threadId,
        snippet = message.body,
        date = message.date,
        read = true,
        title = title,
        photoUri = photoUri,
        isGroupConversation = addresses.size > 1,
        phoneNumber = addresses.first(),
        isScheduled = true,
        usesCustomTitle = cachedConv?.usesCustomTitle == true,
        isArchived = false,
        unreadCount = 0,
    )
    try {
        conversationsDB.insertOrUpdate(conversation)
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

/**
 * Moves the scheduled [messages] to [newThreadId]. Callers pass a snapshot, so this must not write the rows back:
 * re-inserting them brought back a scheduled message that was sent and deleted meanwhile (Retry on a held message,
 * while the refresh after the send moved the thread's scheduled messages).
 */
fun Context.updateScheduledMessagesThreadId(messages: List<Message>, newThreadId: Long) {
    messages.filter { it.isScheduled }.forEach { messagesDB.moveScheduledMessage(it.id, newThreadId) }
}

/**
 * F3: a scheduled message whose time passed without being sent (alarm lost to a force-stop or the phone being off) is
 * never dropped any more: its alarm is set again, in the past, so it goes out at once, late, with the "sent late"
 * notice. Only an empty temporary thread is cleaned up. (The name is from when overdue messages were deleted.)
 */
fun Context.clearExpiredScheduledMessages(threadId: Long, messagesToDelete: List<Message>? = null) {
    val messages = messagesToDelete ?: messagesDB.getScheduledThreadMessages(threadId)
    val cutoff = System.currentTimeMillis() - 1.minutes.inWholeMilliseconds

    try {
        messages.filter { it.isExpiredScheduled(cutoff) && it.type == Sms.MESSAGE_TYPE_QUEUED }.forEach { msg ->
            runCatching { scheduleMessage(msg, warnIfInexact = false) }
        }
        if (messages.isEmpty()) {
            // delete empty temporary thread
            val conversation = conversationsDB.getConversationWithThreadId(threadId)
            if (conversation != null && conversation.isScheduled) {
                conversationsDB.deleteThreadId(threadId)
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
        return
    }
}

fun Context.rescheduleAllScheduledMessages() {
    val scheduledMessages = try {
        messagesDB.getAllScheduledMessages()
    } catch (_: Exception) {
        return
    }

    // Falls back to inexact alarms without exact-alarm access; no toast, this runs from boot and similar broadcasts.
    // A scheduled message whose send failed is held (type FAILED): it goes out only when the user retries it. One
    // that is being sent right now is left alone, and one left in the outbox by a send that died is held, never
    // re-sent (F3).
    scheduledMessages.forEach { message ->
        when (scheduledActionFor(message.type, ScheduledSendClaims.isInFlight(message.id))) {
            ScheduledAction.ARM -> runCatching { scheduleMessage(message, warnIfInexact = false) }
            // M-P4: held as failed, with the "not sent" notification. K1-6: asked again right here, as a send may have
            // claimed it since the list was read; holdDiedScheduledSend posts only when its update changed the row.
            ScheduledAction.HOLD -> if (!ScheduledSendClaims.isInFlight(message.id)) {
                runCatching { holdDiedScheduledSend(message) }
            }
            ScheduledAction.SKIP -> Unit
        }
    }
}

fun Context.getDefaultKeyboardHeight(): Int {
    return resources.getDimensionPixelSize(R.dimen.default_keyboard_height)
}

fun Context.shouldUnarchive(): Boolean {
    return config.isArchiveAvailable && !config.keepConversationsArchived
}

fun Context.copyToUri(src: Uri, dst: Uri) {
    contentResolver.openInputStream(src)?.use { input ->
        contentResolver.openOutputStream(dst, "rwt")?.use { out ->
            input.copyTo(out)
        }
    }
}

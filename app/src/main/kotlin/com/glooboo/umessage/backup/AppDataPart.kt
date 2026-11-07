package com.glooboo.umessage.backup

import com.glooboo.umessage.models.AppDataBackup

/**
 * What a backup is made of. [threadIds] are the exported chats; [wholePhone] is true when every chat is exported,
 * which is when things that belong to no chat (saved texts, scheduled messages, reminders) go along too.
 */
data class AppDataRequest(
    val threadIds: Set<Long>,
    val sms: Boolean,
    val mms: Boolean,
    val wholePhone: Boolean,
)

/**
 * How a backup finds chats. [addressesOf] gives the numbers of a chat's people; [threadFor] the system thread of some
 * people (made if there is none); [conversationFor] the chat of some people only when the phone already lists one.
 */
class ChatLookup(
    val addressesOf: (threadId: Long) -> Set<String>,
    val threadFor: (addresses: Set<String>) -> Long?,
    val conversationFor: (addresses: Set<String>) -> Long? = { null },
)

/** One kind of app data in the [AppDataBackup] entry: reads its part of the phone and puts a backup's part back. */
interface AppDataPart {
    /** [base] with this part's lists filled in. */
    suspend fun collect(request: AppDataRequest, base: AppDataBackup): AppDataBackup

    /** Adds what [data] holds that this phone does not have yet; returns how many items were taken. */
    suspend fun restore(data: AppDataBackup): Int
}

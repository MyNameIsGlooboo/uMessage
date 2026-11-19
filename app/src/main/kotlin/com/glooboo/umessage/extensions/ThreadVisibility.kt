package com.glooboo.umessage.extensions

/**
 * Whether the conversation list leaves a thread out. A thread with no recipients is always left out (S3: it has no
 * number to show or sort, and must not stop the refresh). With [hideBlocked] (0.4.14) a 1:1 chat with a number on the
 * phone's block list is left out too (it lives in Blocked), but a group chat stays visible unless every member is
 * blocked: one blocked member must not hide the chats with people who are not blocked.
 *
 * Provider truth passes [hideBlocked] false: such a chat is read like any other and shown in Spam as blocked (2.1,
 * check 3), and group chats always stay in Messages.
 */
fun isThreadHiddenByBlocks(
    phoneNumbers: List<String>,
    hideBlocked: Boolean = true,
    isBlocked: (String) -> Boolean,
): Boolean = phoneNumbers.isEmpty() || (hideBlocked && phoneNumbers.all(isBlocked))

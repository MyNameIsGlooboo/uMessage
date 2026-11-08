package com.glooboo.umessage.extensions

/**
 * Whether the Messages list leaves a conversation out because of the phone's block list. A 1:1 chat with a blocked
 * number is left out (it lives in Blocked), but a group chat stays visible unless every member is blocked: one
 * blocked member must not hide the chats with people who are not blocked.
 */
fun isThreadHiddenByBlocks(phoneNumbers: List<String>, isBlocked: (String) -> Boolean): Boolean =
    phoneNumbers.isEmpty() || phoneNumbers.all(isBlocked)

package com.glooboo.umessage.models

class Events {
    class RefreshMessages
    /**
     * The conversation list may be out of date. With [threadId] only that chat changed and its saved row is already
     * up to date, so the list does not read every thread again (MC-02).
     */
    class RefreshConversations(val threadId: Long? = null)
}

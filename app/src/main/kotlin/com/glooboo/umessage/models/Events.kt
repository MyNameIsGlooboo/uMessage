package com.glooboo.umessage.models

class Events {
    class RefreshMessages
    /**
     * The conversation list may be out of date. With [threadId] only that chat changed and its saved row is already
     * up to date, so the list does not read every thread again (MC-02). [urgent]: a restore or an import; the full
     * pass runs at once, also while the list is out of sight (J2).
     */
    class RefreshConversations(val threadId: Long? = null, val urgent: Boolean = false)
}

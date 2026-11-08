package com.glooboo.umessage.ui.starred

import android.content.Context
import android.content.Intent
import com.glooboo.umessage.activities.ThreadActivity
import com.glooboo.umessage.helpers.SEARCHED_MESSAGE_ID
import com.glooboo.umessage.helpers.SEARCHED_MESSAGE_IS_MMS
import com.glooboo.umessage.helpers.StarredItem
import com.glooboo.umessage.helpers.THREAD_ID
import com.glooboo.umessage.helpers.THREAD_TITLE

/**
 * What a tap on a starred message opens: its chat, scrolled to that message. The chat pages in older messages until it
 * finds the message (the same jump the search results use), so a star from far back in the history works too.
 */
fun starredOpenIntent(context: Context, item: StarredItem): Intent =
    Intent(context, ThreadActivity::class.java)
        .putExtra(THREAD_ID, item.threadId)
        .putExtra(THREAD_TITLE, item.title)
        .putExtra(SEARCHED_MESSAGE_ID, item.id)
        .putExtra(SEARCHED_MESSAGE_IS_MMS, item.isMMS)

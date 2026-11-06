package com.glooboo.umessage.helpers

import android.content.Context
import com.glooboo.umessage.extensions.config

object ReceiverUtils {

    fun isMessageFilteredOut(context: Context, body: String): Boolean {
        for (blockedKeyword in context.config.blockedKeywords) {
            // A blank keyword (from an old import) would match every message.
            if (blockedKeyword.isNotBlank() && body.contains(blockedKeyword, ignoreCase = true)) {
                return true
            }
        }

        return false
    }
}

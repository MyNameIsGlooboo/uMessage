package com.glooboo.umessage.ui.spam

import android.os.Bundle
import androidx.activity.ComponentActivity
import com.glooboo.umessage.helpers.MainTabLinks

/**
 * Old entry point of the Blocked folder (the blocked-message notification, Settings rows). There is one Blocked
 * page now, the tab on the main screen (BR-12): this only opens that tab and goes away, so no second Blocked screen
 * can be stacked on top of the first.
 */
class BlockedMessagesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(MainTabLinks.intent(this, MainTabLinks.TAB_BLOCKED))
        finish()
    }
}

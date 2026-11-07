package com.glooboo.umessage.ui.spam

import android.content.Context
import com.glooboo.umessage.ui.settings.AppLocale
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.glooboo.umessage.helpers.MainTabLinks
import com.glooboo.umessage.spam.data.BlockedNotifier

/**
 * Old entry point of the Blocked folder (the blocked-message notification, Settings rows). There is one Blocked
 * page now, the tab on the main screen (BR-12): this only opens that tab and goes away, so no second Blocked screen
 * can be stacked on top of the first.
 */
class BlockedMessagesActivity : ComponentActivity() {
    /** I1: puts the in-app language on this screen on Android 8 to 12 (a no-op on 13+). */
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLocale.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // I5: the notifications of blocked messages (and the held-code notice) have done their job.
        BlockedNotifier.cancelAll(this)
        startActivity(MainTabLinks.intent(this, MainTabLinks.TAB_BLOCKED))
        finish()
    }
}

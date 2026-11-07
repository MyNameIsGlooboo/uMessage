package com.glooboo.umessage.ui.spam

import com.glooboo.umessage.ui.settings.AppLocale
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.glooboo.umessage.ui.theme.UMessageTheme
import com.glooboo.umessage.ui.theme.applyUMessageWindow

/**
 * One blocked sender's messages as a read-only thread (owner notes r3), opened from a row of the Blocked tab. Its own
 * screen rather than a page inside the tab, so the tab's bottom bar is not shown under it, as with a normal thread.
 */
class BlockedThreadActivity : ComponentActivity() {
    /** I1: puts the in-app language on this screen on Android 8 to 12 (a no-op on 13+). */
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLocale.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        applyUMessageWindow()
        super.onCreate(savedInstanceState)
        val address = intent?.getStringExtra(EXTRA_ADDRESS)
        if (address.isNullOrEmpty()) {
            finish()
            return
        }
        setContent {
            UMessageTheme {
                BlockedThreadRoute(
                    address = address,
                    onBack = ::finish,
                    onOpenRules = { startActivity(Intent(this, RulesActivity::class.java)) },
                )
            }
        }
    }

    companion object {
        /** String extra: the sender address, the key of its group on the Blocked tab. */
        const val EXTRA_ADDRESS = "com.glooboo.spamless.extra.BLOCKED_ADDRESS"

        fun intent(context: Context, address: String): Intent =
            Intent(context, BlockedThreadActivity::class.java).putExtra(EXTRA_ADDRESS, address)
    }
}

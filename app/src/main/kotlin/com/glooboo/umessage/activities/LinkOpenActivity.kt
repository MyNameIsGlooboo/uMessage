package com.glooboo.umessage.activities

import com.glooboo.umessage.ui.settings.AppLocale
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import org.fossify.commons.extensions.copyToClipboard
import com.glooboo.umessage.R
import com.glooboo.umessage.helpers.threadPrefs
import com.glooboo.umessage.ui.theme.UMessageTheme
import com.glooboo.umessage.ui.thread.LinkSheet
import com.glooboo.umessage.ui.thread.linkCopyText
import com.glooboo.umessage.ui.thread.shareLink

/**
 * I67: the notification "Open link" action. It only shows the link sheet over whatever is on screen (a translucent
 * activity of its own task); the browser starts after the "Open" tap, never straight from the notification.
 */
class LinkOpenActivity : ComponentActivity() {

    /** I1: puts the in-app language on this screen on Android 8 to 12 (a no-op on 13+). */
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLocale.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val link = intent?.getStringExtra(EXTRA_LINK)
        if (link.isNullOrBlank()) {
            finish()
            return
        }
        setContent {
            UMessageTheme {
                LinkSheet(
                    uri = link,
                    trusted = true,
                    onOpen = {
                        open(link)
                        finish()
                    },
                    onCopy = {
                        copyToClipboard(linkCopyText(link))
                        finish()
                    },
                    onShare = {
                        shareLink(this, link)
                        finish()
                    },
                    onDismiss = ::finish,
                    // The action exists for saved contacts only (NotificationHelper), so the link is trusted;
                    // the "lock risky links" setting still applies.
                    lockRisky = threadPrefs.riskyLinksLocked,
                )
            }
        }
    }

    private fun open(link: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
        } catch (_: Exception) {
            Toast.makeText(this, R.string.thread_no_app, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val EXTRA_LINK = "extra_link"

        /** The intent the notification action starts; explicit, in its own task so it never opens the app's screens. */
        fun intent(context: Context, link: String): Intent =
            Intent(context, LinkOpenActivity::class.java)
                .putExtra(EXTRA_LINK, link)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
    }
}

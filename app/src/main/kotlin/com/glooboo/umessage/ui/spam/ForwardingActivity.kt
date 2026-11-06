package com.glooboo.umessage.ui.spam

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import com.glooboo.umessage.activities.SettingsActivity
import com.glooboo.umessage.spam.ForwardingAvailability
import com.glooboo.umessage.ui.settings.SettingsPage
import com.glooboo.umessage.ui.theme.UMessageTheme
import com.glooboo.umessage.ui.theme.applyUMessageWindow

/**
 * Auto-forwarding: the forward rules, their editor, the test send and the app-lock checks. It used to be a third tab
 * of Rules, where the owner could not find it (round 3); now Settings > Forwarding opens it, and Rules is only
 * Allowed and Blocked. The switches about forwarding (pause, code masking, daily limit) stay on Settings' Forwarding
 * page, opened from this screen's options button.
 */
class ForwardingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        applyUMessageWindow()
        super.onCreate(savedInstanceState)
        // The Google Play build has no forwarding: any way in (a stale shortcut, an intent) ends here at once.
        if (!ForwardingAvailability.isAvailable(this)) {
            finish()
            return
        }
        // A thread's "Always forward from this sender": the editor opens filled in, once (not after rotation).
        val forwardSender = intent?.getStringExtra(EXTRA_FORWARD_SENDER)
        if (savedInstanceState == null && !forwardSender.isNullOrBlank()) {
            ViewModelProvider(this, ForwardRulesViewModel.Factory)[ForwardRulesViewModel::class.java]
                .openAddForSender(forwardSender)
        }
        setContent {
            UMessageTheme {
                ForwardingRoute(
                    onBack = ::finish,
                    onOpenAppLockSettings = { openSettingsPage(SettingsPage.AppLock) },
                    onOpenOptions = { openSettingsPage(SettingsPage.Forwarding) },
                )
            }
        }
    }

    /** A Settings page on its own: Back there comes straight back here. */
    private fun openSettingsPage(page: SettingsPage) {
        startActivity(Intent(this, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_PAGE, page.name))
    }

    companion object {
        /**
         * String extra: open the forward rule editor filled in for this sender. The same key as the older
         * `RulesActivity.EXTRA_FORWARD_SENDER` and `ConversationDetailsActivity.EXTRA_FORWARD_SENDER`, so intents
         * built with those keep working.
         */
        const val EXTRA_FORWARD_SENDER = "com.glooboo.spamless.extra.FORWARD_SENDER"

        /** Opens the forwarding screen; with a non-blank [sender] the editor opens filled in for it. */
        fun intent(context: Context, sender: String? = null): Intent =
            Intent(context, ForwardingActivity::class.java).apply {
                sender?.trim()?.takeIf { it.isNotEmpty() }?.let { putExtra(EXTRA_FORWARD_SENDER, it) }
            }
    }
}

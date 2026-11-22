package com.glooboo.umessage.ui.spam

import android.content.Context
import com.glooboo.umessage.ui.settings.AppLocale
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.glooboo.umessage.spam.ForwardingAvailability
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.ui.theme.UMessageTheme
import com.glooboo.umessage.ui.theme.applyUMessageWindow

/**
 * Allowed and blocked rules editor. Rules are backed up on Settings > Backup and restore. Forward rules have their own
 * screen, [ForwardingActivity] (round 3).
 */
class RulesActivity : ComponentActivity() {
    /** I1: puts the in-app language on this screen on Android 8 to 12 (a no-op on 13+). */
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLocale.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        applyUMessageWindow()
        super.onCreate(savedInstanceState)
        // An intent built for the old Forward tab still lands on forwarding, not on the allowed and blocked lists.
        if (savedInstanceState == null && opensForwarding(intent)) {
            // A build without a forwarding screen: such an intent just ends here.
            if (ForwardingAvailability.isAvailable(this)) {
                startActivity(ForwardingActivity.intent(this, intent?.getStringExtra(EXTRA_FORWARD_SENDER)))
            }
            finish()
            return
        }
        setContent {
            UMessageTheme {
                RulesRoute(
                    onBack = ::finish,
                    onOpenBackup = {
                        startActivity(
                            Intent(this, com.glooboo.umessage.activities.SettingsActivity::class.java)
                                .putExtra(com.glooboo.umessage.activities.SettingsActivity.EXTRA_PAGE, "Backup"),
                        )
                    },
                )
            }
        }
    }

    companion object {
        /**
         * Boolean extra kept for older callers: the Forward tab is gone, and an intent with it is passed on to
         * [ForwardingActivity]. New code opens [ForwardingActivity.intent] directly.
         */
        const val EXTRA_OPEN_FORWARD_TAB = "com.glooboo.spamless.extra.OPEN_FORWARD_TAB"

        /** String extra kept for older callers; the same key as [ForwardingActivity.EXTRA_FORWARD_SENDER]. */
        const val EXTRA_FORWARD_SENDER = ForwardingActivity.EXTRA_FORWARD_SENDER

        /** String extra, a [ListKind] name: the tab the screen opens on (D-6: "View rule" opens the block list). */
        const val EXTRA_TAB = "com.glooboo.spamless.extra.RULES_TAB"

        /** The rules screen, on [tab] when given, else on its first tab. */
        fun intent(context: Context, tab: ListKind? = null): Intent =
            Intent(context, RulesActivity::class.java).apply { tab?.let { putExtra(EXTRA_TAB, it.name) } }

        /** The tab named by an [EXTRA_TAB] value; null for none or an unknown name. */
        internal fun tabOf(name: String?): ListKind? = ListKind.entries.firstOrNull { it.name == name }

        /** True when [intent] asked for the old Forward tab or for a forward rule editor. */
        internal fun opensForwarding(intent: Intent?): Boolean {
            if (intent == null) return false
            return intent.getBooleanExtra(EXTRA_OPEN_FORWARD_TAB, false) ||
                !intent.getStringExtra(EXTRA_FORWARD_SENDER).isNullOrBlank()
        }
    }
}

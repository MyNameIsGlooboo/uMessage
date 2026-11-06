package com.glooboo.umessage.ui.spam

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.glooboo.umessage.spam.ForwardingAvailability
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.ui.theme.UMessageTheme
import com.glooboo.umessage.ui.theme.applyUMessageWindow

/**
 * Allowed and blocked rules editor. Rules are backed up on Settings > Backup and restore. Forward rules have their own
 * screen, [ForwardingActivity] (round 3).
 */
class RulesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        applyUMessageWindow()
        super.onCreate(savedInstanceState)
        // An intent built for the old Forward tab still lands on forwarding, not on the allowed and blocked lists.
        if (savedInstanceState == null && opensForwarding(intent)) {
            // The Google Play build has no forwarding screen: such an intent just ends here.
            if (ForwardingAvailability.isAvailable(this)) {
                startActivity(ForwardingActivity.intent(this, intent?.getStringExtra(EXTRA_FORWARD_SENDER)))
            }
            finish()
            return
        }
        val openMigration = intent?.getBooleanExtra(EXTRA_OPEN_MIGRATION, false) == true
        val prefill = prefillRule(intent)
        setContent {
            UMessageTheme {
                RulesRoute(
                    onBack = ::finish,
                    onOpenSystemBlockList = { startActivity(Intent(this, BlockedNumbersActivity::class.java)) },
                    onOpenBackup = {
                        startActivity(
                            Intent(this, com.glooboo.umessage.activities.SettingsActivity::class.java)
                                .putExtra(com.glooboo.umessage.activities.SettingsActivity.EXTRA_PAGE, "Backup"),
                        )
                    },
                    openMigration = openMigration,
                    prefill = prefill,
                )
            }
        }
    }

    companion object {
        /** Boolean extra: open the "move older block lists into rules" dialog once the lists are read. */
        const val EXTRA_OPEN_MIGRATION = "com.glooboo.spamless.extra.OPEN_MIGRATION"

        /**
         * Boolean extra kept for older callers: the Forward tab is gone, and an intent with it is passed on to
         * [ForwardingActivity]. New code opens [ForwardingActivity.intent] directly.
         */
        const val EXTRA_OPEN_FORWARD_TAB = "com.glooboo.spamless.extra.OPEN_FORWARD_TAB"

        /** String extra kept for older callers; the same key as [ForwardingActivity.EXTRA_FORWARD_SENDER]. */
        const val EXTRA_FORWARD_SENDER = ForwardingActivity.EXTRA_FORWARD_SENDER

        /** String extras (a [RuleType] name and a value): open the add sheet on Allowed with that rule filled in. */
        const val EXTRA_PREFILL_TYPE = "com.glooboo.spamless.extra.PREFILL_TYPE"
        const val EXTRA_PREFILL_PATTERN = "com.glooboo.spamless.extra.PREFILL_PATTERN"

        /** The allow rule [intent] asks to start from, or null. */
        internal fun prefillRule(intent: Intent?): SimilarRuleSuggestion.Suggestion? {
            val name = intent?.getStringExtra(EXTRA_PREFILL_TYPE).orEmpty()
            val type = runCatching { RuleType.valueOf(name) }.getOrNull()
            val pattern = intent?.getStringExtra(EXTRA_PREFILL_PATTERN)?.takeIf { it.isNotBlank() }
            return if (type != null && pattern != null) SimilarRuleSuggestion.Suggestion(type, pattern) else null
        }

        /** True when [intent] asked for the old Forward tab or for a forward rule editor. */
        internal fun opensForwarding(intent: Intent?): Boolean {
            if (intent == null) return false
            return intent.getBooleanExtra(EXTRA_OPEN_FORWARD_TAB, false) ||
                !intent.getStringExtra(EXTRA_FORWARD_SENDER).isNullOrBlank()
        }
    }
}

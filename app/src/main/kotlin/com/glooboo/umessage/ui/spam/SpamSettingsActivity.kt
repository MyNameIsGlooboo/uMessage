package com.glooboo.umessage.ui.spam

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.glooboo.umessage.helpers.MainTabLinks
import com.glooboo.umessage.ui.log.DecisionLogActivity
import com.glooboo.umessage.ui.theme.UMessageTheme
import com.glooboo.umessage.ui.theme.applyUMessageWindow

/** The one Spam protection page (U02): unknown-sender mode, rules, retention, countries, links and alerts. */
class SpamSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        applyUMessageWindow()
        super.onCreate(savedInstanceState)
        setContent {
            UMessageTheme {
                SettingsRoute(
                    onBack = ::finish,
                    onOpenRules = { open(RulesActivity::class.java) },
                    // The one Blocked page is the main screen's tab (BR-12); this closes Settings on the way there.
                    onOpenBlocked = { startActivity(MainTabLinks.intent(this, MainTabLinks.TAB_BLOCKED)) },
                    onOpenDecisionLog = if (decisionLogAvailable()) {
                        { open(DecisionLogActivity::class.java) }
                    } else {
                        null
                    },
                )
            }
        }
    }

    private fun open(target: Class<*>) = startActivity(Intent(this, target))

    private fun decisionLogAvailable() =
        runCatching { Class.forName("com.glooboo.umessage.spam.SpamLog") }.isSuccess
}

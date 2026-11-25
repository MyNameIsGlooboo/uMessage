package com.glooboo.umessage.ui.spam

import android.content.Context
import com.glooboo.umessage.activities.ManageBlockedKeywordsActivity
import com.glooboo.umessage.extensions.config
import com.glooboo.umessage.ui.settings.AppLocale
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.glooboo.umessage.ui.log.DecisionLogActivity
import com.glooboo.umessage.ui.theme.UMessageTheme
import com.glooboo.umessage.ui.theme.applyUMessageWindow

/** The one Spam protection page (U02): unknown-sender mode, rules, retention, countries, links and alerts. */
class SpamSettingsActivity : ComponentActivity() {
    /** B3: the old blocked words list already has its own page; the moved row shows only while it still has words. */
    private var showOldBlockedWords by mutableStateOf(false)

    /** I1: puts the in-app language on this screen on Android 8 to 12 (a no-op on 13+). */
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLocale.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        applyUMessageWindow()
        super.onCreate(savedInstanceState)
        showOldBlockedWords = config.blockedKeywords.isNotEmpty()
        setContent {
            UMessageTheme {
                SettingsRoute(
                    onBack = ::finish,
                    onOpenRules = { open(RulesActivity::class.java) },
                    onOpenDecisionLog = if (decisionLogAvailable()) {
                        { open(DecisionLogActivity::class.java) }
                    } else {
                        null
                    },
                    onOpenBlockedNumbers = { open(BlockedNumbersActivity::class.java) },
                    onOpenBlockedKeywords = { open(ManageBlockedKeywordsActivity::class.java) },
                    showOldBlockedWords = showOldBlockedWords,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        showOldBlockedWords = config.blockedKeywords.isNotEmpty()
    }

    private fun open(target: Class<*>) = startActivity(Intent(this, target))

    private fun decisionLogAvailable() =
        runCatching { Class.forName("com.glooboo.umessage.spam.SpamLog") }.isSuccess
}

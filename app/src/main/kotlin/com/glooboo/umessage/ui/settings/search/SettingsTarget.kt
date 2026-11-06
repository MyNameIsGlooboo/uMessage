package com.glooboo.umessage.ui.settings.search

import com.glooboo.umessage.ui.settings.SettingsActions
import com.glooboo.umessage.ui.settings.SettingsPage

/**
 * Where a search result goes. A page of the Settings screen opens inside it; the others are the screens and dialogs
 * the rows of the main list open, so a result leads where tapping its row would.
 */
enum class SettingsTarget(private val page: SettingsPage? = null) {
    SPAM(SettingsPage.Spam),
    FORWARD_OPTIONS(SettingsPage.Forwarding),
    NOTIFICATIONS(SettingsPage.Notifications),
    TYPING(SettingsPage.Typing),
    STORAGE(SettingsPage.Storage),
    BACKUP(SettingsPage.Backup),
    APP_LOCK(SettingsPage.AppLock),
    ABOUT(SettingsPage.About),
    LICENSES(SettingsPage.Licenses),

    /** The Appearance screen (theme, colours, text size, font, calendar). */
    APPEARANCE,

    /** The full Spam protection screen (retention, countries, links, alerts). */
    SPAM_SETTINGS,
    SPAM_RULES,
    BLOCKED_NUMBERS,
    DECISION_LOG,

    /** The Forwarding screen with the forward rules. */
    FORWARD_RULES,
    LANGUAGE,
    DEFAULT_SIM,
    ;

    /** True for a page of the Settings screen itself, which replaces the search there. */
    val inScreen: Boolean get() = page != null

    /** Opens the target: [openPage] shows a page of the Settings screen, the rest go through [actions]. */
    fun open(openPage: (SettingsPage) -> Unit, actions: SettingsActions) {
        page?.let { return openPage(it) }
        when (this) {
            APPEARANCE -> actions.onTheme()
            SPAM_SETTINGS -> actions.onOpenSpamSettings()
            SPAM_RULES -> actions.onOpenRules()
            BLOCKED_NUMBERS -> actions.onOpenBlockedNumbers()
            DECISION_LOG -> actions.onOpenDecisionLog()
            FORWARD_RULES -> actions.onOpenForwardRules()
            LANGUAGE -> actions.onLanguage()
            DEFAULT_SIM -> actions.onChooseDefaultSim()
            else -> Unit
        }
    }

    companion object {
        /** The targets that belong to forwarding; the Google Play build has none of them. */
        val FORWARDING_TARGETS: Set<SettingsTarget> = setOf(FORWARD_OPTIONS, FORWARD_RULES)
    }
}

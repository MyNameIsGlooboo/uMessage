package com.glooboo.umessage.ui.main

/** What decides which tabs the bottom bar shows. */
data class MainTabInputs(
    /** The phone supports archiving at all. */
    val archiveAvailable: Boolean,
    /** Conversations in the archive right now. Does not decide the Archived tab: it is there even when this is 0. */
    val archivedCount: Int,
    /** Forward rules that exist, enabled or not. */
    val forwardRuleCount: Int,
    /** The user's own choice for the Forwarded tab: null = never chosen (the tab shows), false = hidden on purpose. */
    val showForwarded: Boolean?,
    /** Failed forwards still listed in the forward log. */
    val failedForwards: Int = 0,
)

object MainTabPolicy {

    /**
     * Messages and Blocked always; Archived whenever the phone supports archiving, also from the first launch with an
     * empty archive (the tab then shows its empty state); Forwarded as soon as a forward rule exists, unless the user
     * hid the tab. Order: Messages, Blocked, Forwarded, Archived.
     *
     * [selected] keeps Forwarded in the bar when it was opened on purpose from Settings ([forwardedOpened]) although
     * the tab is switched off. After the last rule is deleted the Forwarded tab stays
     * while failed forwards are still listed (they are the only way to see what did not go out), then it leaves
     * unless it was opened on purpose.
     */
    fun visibleTabs(inputs: MainTabInputs, selected: MainTab, forwardedOpened: Boolean = false): List<MainTab> =
        buildList {
            add(MainTab.Messages)
            add(MainTab.Blocked)
            val hasForwards = inputs.forwardRuleCount > 0 || inputs.failedForwards > 0
            val forwardedOn = hasForwards && inputs.showForwarded != false
            if (forwardedOn || (forwardedOpened && selected == MainTab.Forwarded)) add(MainTab.Forwarded)
            if (inputs.archiveAvailable) add(MainTab.Archived)
        }

    /** The tab to show: [selected] when the bar has it, else Messages. */
    fun effectiveTab(tabs: List<MainTab>, selected: MainTab): MainTab =
        if (selected in tabs) selected else MainTab.Messages

    /** Maps a [com.glooboo.umessage.helpers.MainTabLinks] value to a tab. */
    fun fromLink(value: String?): MainTab? = when (value) {
        com.glooboo.umessage.helpers.MainTabLinks.TAB_ARCHIVED -> MainTab.Archived
        com.glooboo.umessage.helpers.MainTabLinks.TAB_FORWARDED -> MainTab.Forwarded
        com.glooboo.umessage.helpers.MainTabLinks.TAB_BLOCKED -> MainTab.Blocked
        com.glooboo.umessage.helpers.MainTabLinks.TAB_MESSAGES -> MainTab.Messages
        else -> null
    }
}

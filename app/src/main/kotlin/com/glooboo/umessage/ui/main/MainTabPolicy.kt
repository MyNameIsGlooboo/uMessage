package com.glooboo.umessage.ui.main

/** What decides which tabs the bottom bar shows. */
data class MainTabInputs(
    /** The phone supports archiving at all. */
    val archiveAvailable: Boolean,
    /** Conversations in the archive right now. */
    val archivedCount: Int,
    /** Forward rules that exist, enabled or not. */
    val forwardRuleCount: Int,
    /** The user's answer to "show a Forwarded tab"; null = not asked yet. */
    val showForwarded: Boolean?,
    /** Failed forwards still listed in the forward log. */
    val failedForwards: Int = 0,
)

object MainTabPolicy {

    /**
     * Messages and Blocked always; Forwarded only when a forward rule exists and the user switched the tab on; Archived
     * only while something is archived. Order: Messages, Blocked, Forwarded, Archived.
     *
     * [selected] keeps its own tab in the bar so it never vanishes under the user's finger: Archived after the last
     * chat was unarchived (it leaves once the user does), and Forwarded when it was opened on purpose from Settings
     * ([forwardedOpened]) although the tab is switched off. After the last rule is deleted the Forwarded tab stays
     * while failed forwards are still listed (they are the only way to see what did not go out), then it leaves
     * unless it was opened on purpose.
     */
    fun visibleTabs(inputs: MainTabInputs, selected: MainTab, forwardedOpened: Boolean = false): List<MainTab> =
        buildList {
            add(MainTab.Messages)
            add(MainTab.Blocked)
            val forwardedOn = (inputs.forwardRuleCount > 0 || inputs.failedForwards > 0) && inputs.showForwarded == true
            if (forwardedOn || (forwardedOpened && selected == MainTab.Forwarded)) add(MainTab.Forwarded)
            val archivedOn = inputs.archivedCount > 0 || selected == MainTab.Archived
            if (inputs.archiveAvailable && archivedOn) add(MainTab.Archived)
        }

    /** The tab to show: [selected] when the bar has it, else Messages. */
    fun effectiveTab(tabs: List<MainTab>, selected: MainTab): MainTab =
        if (selected in tabs) selected else MainTab.Messages

    /** Maps a [com.glooboo.umessage.helpers.MainTabLinks] value to a tab. */
    fun fromLink(value: String?): MainTab? = when (value) {
        com.glooboo.umessage.helpers.MainTabLinks.TAB_ARCHIVED -> MainTab.Archived
        com.glooboo.umessage.helpers.MainTabLinks.TAB_FORWARDED -> MainTab.Forwarded
        com.glooboo.umessage.helpers.MainTabLinks.TAB_BLOCKED -> MainTab.Blocked
        else -> null
    }
}

package com.glooboo.umessage.ui.thread

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThreadMenuLayoutTest {

    private fun layout(
        numbers: List<String> = listOf("+15550100"),
        saved: Boolean = false,
        recycleBin: Boolean = false,
        archived: Boolean = false,
        muted: Boolean = false,
        hidden: Boolean = false,
        blocked: String? = null,
    ): ThreadMenuLayout {
        val menu = buildThreadMenu(
            ThreadMenuInput(
                recipients = ThreadRecipients.fromNumbers(numbers), hasItems = true, hasConversation = true,
                isArchived = archived, isRecycleBin = recycleBin, archiveAvailable = true,
                singleNumberIsSaved = saved, reportedSender = null, blockedSender = blocked,
                pinShortcutSupported = true, muted = muted, hiddenChat = hidden,
            )
        )
        return layoutThreadMenu(menu)
    }

    private fun ThreadMenuLayout.sheet(group: ThreadMoreGroup) =
        more.firstOrNull { it.group == group }?.actions.orEmpty()

    @Test
    fun anUnknownSenderGetsTheShortListAndAFullSheet() {
        val layout = layout()
        assertThat(layout.top).containsExactly(
            ThreadMenuAction.Details, ThreadMenuAction.Search, ThreadMenuAction.Mute,
            ThreadMenuAction.Archive, ThreadMenuAction.Block,
        ).inOrder()
        assertThat(layout.sheet(ThreadMoreGroup.ShareExport)).containsExactly(
            ThreadMenuAction.ShareAsText, ThreadMenuAction.ExportConversation, ThreadMenuAction.AddToHomeScreen,
        ).inOrder()
        assertThat(layout.sheet(ThreadMoreGroup.Organize)).containsExactly(
            ThreadMenuAction.Labels, ThreadMenuAction.Hide, ThreadMenuAction.MarkUnread,
        ).inOrder()
        assertThat(layout.sheet(ThreadMoreGroup.Sender)).containsExactly(
            ThreadMenuAction.CopyNumber, ThreadMenuAction.AddToContact, ThreadMenuAction.AllowSender,
            ThreadMenuAction.ReportSpam, ThreadMenuAction.AlwaysForward, ThreadMenuAction.ManagePeople,
        ).inOrder()
        assertThat(layout.more.last().group).isEqualTo(ThreadMoreGroup.Danger)
        assertThat(layout.sheet(ThreadMoreGroup.Danger)).containsExactly(ThreadMenuAction.Delete)
    }

    @Test
    fun theMenuNeverListsMoreThanSixRows() {
        val kinds = listOf(
            layout(),
            layout(saved = true, muted = true, archived = true),
            layout(blocked = "+15550100", hidden = true),
            layout(numbers = listOf("+15550100", "+15550101")),
            layout(numbers = listOf("AX-SHOP")),
        )
        kinds.forEach {
            val rows = it.top.size + if (it.hasMore) 1 else 0
            assertThat(rows).isAtMost(ThreadMenuLayout.MAX_ENTRIES)
        }
    }

    @Test
    fun muteArchiveAndBlockFlipTheirOwnEntry() {
        val layout = layout(muted = true, archived = true, blocked = "+15550100")
        assertThat(layout.top).containsAtLeast(
            ThreadMenuAction.Unmute, ThreadMenuAction.Unarchive, ThreadMenuAction.Unblock,
        )
        assertThat(layout.top).containsNoneOf(
            ThreadMenuAction.Mute, ThreadMenuAction.Archive, ThreadMenuAction.Block,
        )
    }

    @Test
    fun aSenderOnlyPartlyBlockedKeepsBothEntriesWithinSixRows() {
        val layout = layout(blocked = "+15550100")
        assertThat(layout.top).contains(ThreadMenuAction.Unblock)
        assertThat(layout.top).doesNotContain(ThreadMenuAction.Block)
        assertThat(layout.sheet(ThreadMoreGroup.Sender).first()).isEqualTo(ThreadMenuAction.Block)
    }

    @Test
    fun aGroupHasNoBlockAndOffersRenameInOrganize() {
        val layout = layout(numbers = listOf("+15550100", "+15550101"))
        assertThat(layout.top).doesNotContain(ThreadMenuAction.Block)
        assertThat(layout.sheet(ThreadMoreGroup.Organize)).contains(ThreadMenuAction.Rename)
        assertThat(layout.sheet(ThreadMoreGroup.Sender)).containsExactly(ThreadMenuAction.ManagePeople)
    }

    @Test
    fun aSavedContactHasNoAllowOrAddToContact() {
        val sender = layout(saved = true).sheet(ThreadMoreGroup.Sender)
        assertThat(sender).containsNoneOf(ThreadMenuAction.AllowSender, ThreadMenuAction.AddToContact)
        assertThat(sender).contains(ThreadMenuAction.CopyNumber)
    }

    @Test
    fun aShortCodeWithLettersHasNoNumberEntries() {
        val sender = layout(numbers = listOf("AX-SHOP")).sheet(ThreadMoreGroup.Sender)
        assertThat(sender).containsNoneOf(
            ThreadMenuAction.CopyNumber, ThreadMenuAction.AddToContact, ThreadMenuAction.ManagePeople,
        )
        assertThat(sender).contains(ThreadMenuAction.ReportSpam)
    }

    @Test
    fun aHiddenChatOffersUnhideInOrganize() {
        val organize = layout(hidden = true).sheet(ThreadMoreGroup.Organize)
        assertThat(organize).contains(ThreadMenuAction.Unhide)
        assertThat(organize).doesNotContain(ThreadMenuAction.Hide)
    }

    @Test
    fun aBlockedSenderStaysAllowableAndExplainable() {
        val sender = layout(blocked = "+15550100").sheet(ThreadMoreGroup.Sender)
        assertThat(sender).containsAtLeast(ThreadMenuAction.AllowSender, ThreadMenuAction.WhyDecided)
    }

    @Test
    fun theRecycleBinListsRestoreAndDeleteAndNoSheet() {
        val layout = layout(recycleBin = true)
        assertThat(layout.top).containsExactly(ThreadMenuAction.Restore, ThreadMenuAction.Delete).inOrder()
        assertThat(layout.hasMore).isFalse()
    }

    @Test
    fun anEmptyMenuHasNoMoreEntry() {
        val layout = layoutThreadMenu(ThreadMenuState())
        assertThat(layout.top).isEmpty()
        assertThat(layout.hasMore).isFalse()
    }
}

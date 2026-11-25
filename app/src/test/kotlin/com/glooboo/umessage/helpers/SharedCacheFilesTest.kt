package com.glooboo.umessage.helpers

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** B2: conversation shares and decision log exports share a cache folder but never each other's files. */
class SharedCacheFilesTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun file(dir: File, name: String, modified: Long) =
        File(dir, name).apply { writeText("x"); setLastModified(modified) }

    @Test
    fun eachFeatureNamesItsFilesWithItsOwnPrefix() {
        assertThat(SharedCacheFiles.conversationName(1_700_000_000_000, "txt"))
            .isEqualTo("conversation-1700000000000.txt")
        assertThat(SharedCacheFiles.conversationName(1_700_000_000_000, "pdf"))
            .isEqualTo("conversation-1700000000000.pdf")
        assertThat(SharedCacheFiles.decisionLogName(1_700_000_000_000))
            .isEqualTo("1700000000000-uMessage-decisions.csv")
    }

    @Test
    fun eachFeatureRecognisesOnlyItsOwnFiles() {
        assertThat(SharedCacheFiles.isConversation("conversation-1.txt")).isTrue()
        assertThat(SharedCacheFiles.isConversation("1700000000000-uMessage-decisions.csv")).isFalse()
        assertThat(SharedCacheFiles.isDecisionLog("1700000000000-uMessage-decisions.csv")).isTrue()
        assertThat(SharedCacheFiles.isDecisionLog("conversation-1.txt")).isFalse()
    }

    @Test
    fun oneFeatureNeverDeletesTheOtherFeaturesFiles() {
        val dir = folder.newFolder()
        val conversation = file(dir, SharedCacheFiles.conversationName(1_000, "txt"), 1_000)
        val decision = file(dir, SharedCacheFiles.decisionLogName(1_000), 1_000)

        SharedCacheFiles.deleteOwn(dir, SharedCacheFiles::isConversation, now = 2_000, maxAgeMs = 0)

        assertThat(conversation.exists()).isFalse()
        assertThat(decision.exists()).isTrue()

        val laterConversation = file(dir, SharedCacheFiles.conversationName(3_000, "txt"), 3_000)
        SharedCacheFiles.deleteOwn(dir, SharedCacheFiles::isDecisionLog, now = 4_000, maxAgeMs = 0)

        assertThat(laterConversation.exists()).isTrue()
        assertThat(decision.exists()).isFalse()
    }

    @Test
    fun oldOwnFilesAreRemovedAndRecentOnesStay() {
        val dir = folder.newFolder()
        val day = SharedCacheFiles.KEEP_MS
        val oldConversation = file(dir, SharedCacheFiles.conversationName(1_000, "txt"), 1_000)
        val recentConversation = file(dir, SharedCacheFiles.conversationName(9_000, "txt"), 9_000)
        val oldDecision = file(dir, SharedCacheFiles.decisionLogName(1_000), 1_000)

        val deleted = SharedCacheFiles.deleteOwn(
            dir, SharedCacheFiles::isConversation, now = day + 1_000, maxAgeMs = day,
        )

        assertThat(deleted).isEqualTo(1)
        assertThat(oldConversation.exists()).isFalse()
        assertThat(recentConversation.exists()).isTrue()
        assertThat(oldDecision.exists()).isTrue()
    }
}

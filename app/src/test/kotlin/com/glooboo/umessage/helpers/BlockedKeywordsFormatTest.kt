package com.glooboo.umessage.helpers

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BlockedKeywordsFormatTest {

    @Test
    fun blanksAndOneLetterEntriesAreDropped() {
        // SU-35: "a, ,b" used to add a blank keyword that matched nearly every message.
        assertThat(BlockedKeywordsFormat.parse("a, ,b,win")).containsExactly("win")
        assertThat(BlockedKeywordsFormat.parse("  \n , \n")).isEmpty()
        assertThat(BlockedKeywordsFormat.parse("")).isEmpty()
    }

    @Test
    fun keywordWithACommaSurvivesExportAndImport() {
        // SU-36: one phrase used to come back as two, and the short half blocked too much.
        val keywords = setOf("free, gift card", "lottery")
        assertThat(BlockedKeywordsFormat.parse(BlockedKeywordsFormat.export(keywords)))
            .containsExactly("free, gift card", "lottery").inOrder()
        assertThat(BlockedKeywordsFormat.parse(BlockedKeywordsFormat.export(setOf("free, gift card"))))
            .containsExactly("free, gift card")
    }

    @Test
    fun oldCommaFilesAreStillRead() {
        assertThat(BlockedKeywordsFormat.parse("lottery,free gift,prize")).containsExactly("lottery", "free gift", "prize")
    }

    @Test
    fun duplicatesIgnoreCase() {
        // SU-38: "Free" and "free" are the same keyword to the filter.
        assertThat(BlockedKeywordsFormat.parse("Free\nfree\nFREE\n")).containsExactly("Free")
        assertThat(BlockedKeywordsFormat.add(setOf("Free"), " free ")).containsExactly("Free")
        assertThat(BlockedKeywordsFormat.add(setOf("Free"), "prize")).containsExactly("Free", "prize")
        assertThat(BlockedKeywordsFormat.add(setOf("Free"), " ")).containsExactly("Free")
    }

    @Test
    fun listIsSortedWithoutRegardToCase() {
        assertThat(BlockedKeywordsFormat.sorted(listOf("b", "A", "c"))).containsExactly("A", "b", "c").inOrder()
    }
}

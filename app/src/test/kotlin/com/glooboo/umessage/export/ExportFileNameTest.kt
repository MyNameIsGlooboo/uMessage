package com.glooboo.umessage.export

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** D15: exported files get unique, safe names. */
class ExportFileNameTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun theChatNameIsReducedToLettersAndDigits() {
        assertThat(ExportFileName.sanitize("../../Mom & Dad: 2025?")).isEqualTo("Mom-Dad-2025")
        assertThat(ExportFileName.sanitize("علی رضایی")).isEqualTo("علی-رضایی")
        assertThat(ExportFileName.sanitize("///")).isEqualTo("chat")
        assertThat(ExportFileName.sanitize("a".repeat(100))).hasLength(40)
    }

    @Test
    fun twoExportsOfTheSameChatNeverShareAName() {
        val dir = folder.newFolder()
        val first = ExportFileName.unique(dir, "Mom", "pdf", 1_700_000_000_000)
        first.writeText("one")
        val second = ExportFileName.unique(dir, "Mom", "pdf", 1_700_000_000_000)

        assertThat(second.name).isNotEqualTo(first.name)
        assertThat(first.name).startsWith("uMessage-Mom-")
        assertThat(first.name).endsWith(".pdf")
        assertThat(second.parentFile).isEqualTo(dir)
    }

    @Test
    fun onlyOldExportFilesAreDeleted() {
        val dir = folder.newFolder()
        val old = java.io.File(dir, "uMessage-A-1.txt").apply { writeText("x"); setLastModified(1_000) }
        val recent = java.io.File(dir, "uMessage-B-1.txt").apply { writeText("x"); setLastModified(9_000) }
        val other = java.io.File(dir, "other.txt").apply { writeText("x"); setLastModified(1_000) }

        ExportFileName.deleteOld(dir, now = 10_000, maxAgeMs = 5_000)

        assertThat(old.exists()).isFalse()
        assertThat(recent.exists()).isTrue()
        assertThat(other.exists()).isTrue()
    }
}

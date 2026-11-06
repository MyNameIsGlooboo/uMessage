package com.glooboo.umessage.helpers.smartreply.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Loading: tolerant of missing blocks, unknown keys, a BOM and bad files; the language must equal the file name. */
class EngineTablesTest {

    @Test
    fun aTableWithOnlyLanguageAndScriptParses() {
        val table = EngineTables.parse("""{"language": "xx", "script": "latin"}""")
        assertThat(table.maxWords).isEqualTo(EngineTable.DEFAULT_MAX_WORDS)
        assertThat(table.yesNo.starters).isEmpty()
        assertThat(table.formal).isEmpty()
        assertThat(table.choice.maxOptionWords).isEqualTo(ChoiceBlock.DEFAULT_MAX_OPTION_WORDS)
    }

    @Test
    fun unknownKeysAndABomAreTolerated() {
        val table = EngineTables.parse("﻿" + """{"language": "xx", "script": "latin", "note": 1}""")
        assertThat(table.language).isEqualTo("xx")
    }

    @Test
    fun whenAndWhereBlocksUseTheirJsonNames() {
        val table = EngineTestData.fixtureTable()
        assertThat(table.whenBlock.cues).contains("when")
        assertThat(table.whereBlock.cues).contains("where are you")
    }

    @Test
    fun aFileWhoseLanguageDiffersFromItsNameIsSkipped() {
        val text = EngineTestData.fixtureText()
        val tables = EngineTables.parseAll(listOf("en.json" to text, "fr.json" to text, "de.json" to "{broken"))
        assertThat(tables.map { it.language }).containsExactly("en")
    }
}

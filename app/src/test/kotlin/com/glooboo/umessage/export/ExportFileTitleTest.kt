package com.glooboo.umessage.export

import org.junit.Assert.assertEquals
import org.junit.Test

class ExportFileTitleTest {
    @Test fun keepsLettersAndDigitsJoinedByDashes() {
        assertEquals("Sara-Smith-2", exportFileTitle("Sara  Smith / 2"))
    }

    @Test fun removesPathCharactersAndEdgeDashes() {
        assertEquals("a-b", exportFileTitle("../a:b?*"))
    }

    @Test fun emptyWhenNothingUsable() {
        assertEquals("", exportFileTitle("+-- //"))
    }

    @Test fun isShortened() {
        assertEquals(30, exportFileTitle("x".repeat(100)).length)
    }
}

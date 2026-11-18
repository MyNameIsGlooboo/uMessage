package com.glooboo.umessage.ui.setup

import android.Manifest
import com.glooboo.umessage.ui.onboarding.PermissionItem
import com.glooboo.umessage.ui.onboarding.permissionItems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Setup screen's headline: required rows decide, an open optional step is mentioned but never blocks. */
class SetupHeadlineTest {

    private fun rows(contacts: SetupMark) = listOf(
        SetupRowState(SetupMark.DONE, required = true),
        SetupRowState(contacts, required = !permissionItems().first { it.isContacts() }.optional),
    )

    private fun PermissionItem.isContacts() =
        Manifest.permission.READ_CONTACTS in permissions

    @Test
    fun `contacts are optional, so a refused contacts permission does not hold the headline back`() {
        assertTrue(permissionItems().first { it.isContacts() }.optional)
        assertTrue(setupRequiredDone(rows(SetupMark.NOT_DONE)))
        assertEquals(SetupHeadline.ALL_SET, setupHeadline(rows(SetupMark.NOT_DONE), optionalOpen = false))
    }

    @Test
    fun `everything required done with the battery step open says one optional step is left`() {
        assertEquals(SetupHeadline.OPTIONAL_LEFT, setupHeadline(rows(SetupMark.DONE), optionalOpen = true))
    }

    @Test
    fun `an open required row wins over the optional step`() {
        val open = listOf(SetupRowState(SetupMark.NOT_DONE, required = true))
        assertEquals(SetupHeadline.SOME_OPEN, setupHeadline(open, optionalOpen = true))
        assertEquals(SetupHeadline.SOME_OPEN, setupHeadline(open, optionalOpen = false))
    }
}

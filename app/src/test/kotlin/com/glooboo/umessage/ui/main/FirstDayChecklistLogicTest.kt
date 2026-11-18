package com.glooboo.umessage.ui.main

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.R
import com.glooboo.umessage.helpers.DefaultSmsRole
import com.glooboo.umessage.helpers.W3cPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The first-day card: when it can be closed, what its title says, the role button and the optional battery step. */
@RunWith(AndroidJUnit4::class)
class FirstDayChecklistLogicTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val required = ChecklistStep.entries.filter { it.required }.toSet()

    @Test
    fun `a required step left open blocks closing until the card has been shown for seven days`() {
        val open = required - ChecklistStep.Notifications
        assertFalse(checklistCanClose(open, shownForMs = 0L))
        assertFalse(checklistCanClose(open, shownForMs = CHECKLIST_FORCE_CLOSE_MS - 1))
        assertTrue(checklistCanClose(open, shownForMs = CHECKLIST_FORCE_CLOSE_MS))
    }

    @Test
    fun `a title never says the basics are done while a required step cannot be done`() {
        val noRole = setOf(ChecklistStep.DefaultSms)
        val rest = ChecklistStep.entries.toSet() - ChecklistStep.DefaultSms
        assertEquals(ChecklistTitle.Count, checklistTitle(rest, noRole))
        assertEquals(ChecklistTitle.Count, checklistTitle(required - ChecklistStep.DefaultSms, noRole))
        assertEquals(ChecklistTitle.Steps, checklistTitle(setOf(ChecklistStep.Contacts), noRole))
        assertEquals(ChecklistTitle.BasicsDone, checklistTitle(required))
        assertEquals(ChecklistTitle.AllDone, checklistTitle(ChecklistStep.entries.toSet()))
    }

    @Test
    fun `a refused role request turns the button into Open settings and the next tap opens settings`() {
        val request = DefaultSmsRole.Status.Request(Intent())
        assertEquals(RoleStepAction.ASK, roleStepAction(request, refused = false))
        assertEquals(RoleStepAction.OPEN_SETTINGS, roleStepAction(request, refused = true))
        assertEquals(RoleStepAction.OPEN_SETTINGS, roleStepAction(DefaultSmsRole.Status.Unavailable, refused = false))
        assertEquals(RoleStepAction.NOTHING, roleStepAction(DefaultSmsRole.Status.Held, refused = true))

        assertEquals(R.string.w3c_checklist_action_set, actionLabel(ChecklistStep.DefaultSms, roleRefused = false))
        assertEquals(R.string.onb_perm_open_settings, actionLabel(ChecklistStep.DefaultSms, roleRefused = true))
        assertEquals(R.string.w3c_checklist_action_open, actionLabel(ChecklistStep.Contacts, roleRefused = true))
    }

    @Test
    fun `the battery step is listed, and open, exactly while the battery guide is needed`() {
        val prefs = W3cPrefs.get(app)
        val needed = readChecklist(app, prefs, forwardRuleCount = 0, batteryListed = true)
        assertTrue(ChecklistStep.Battery in needed.steps)
        assertFalse(ChecklistStep.Battery in needed.done)

        val notNeeded = readChecklist(app, prefs, forwardRuleCount = 0, batteryListed = false)
        assertFalse(ChecklistStep.Battery in notNeeded.steps)
        assertTrue(ChecklistStep.Battery in notNeeded.done)
    }
}

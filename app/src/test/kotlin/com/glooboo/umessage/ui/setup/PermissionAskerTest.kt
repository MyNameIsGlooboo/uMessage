package com.glooboo.umessage.ui.setup

import android.Manifest
import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.helpers.W3cPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** When the Setup page decides that Android will not ask for a permission again. */
@RunWith(AndroidJUnit4::class)
class PermissionAskerTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val perm = Manifest.permission.READ_CONTACTS
    private var rationale = false

    private fun asker() = PermissionAsker(
        context = app,
        permanentlyDenied = mutableStateOf(emptySet()),
        rationaleBefore = mutableStateOf(emptySet()),
        prefs = W3cPrefs.get(app),
        rationale = { rationale },
    )

    private fun PermissionAsker.ask(rationaleBeforeAsk: Boolean, rationaleAfterAnswer: Boolean, granted: Boolean) {
        rationale = rationaleBeforeAsk
        var launched = false
        launch = { launched = true }
        request(listOf(perm))
        assertTrue(launched)
        rationale = rationaleAfterAnswer
        onResult(mapOf(perm to granted))
    }

    @Test
    fun `the first ask refused by the user is not blocked`() {
        val asker = asker()
        // The user taps Don't allow: Android now wants a rationale shown, so the next ask will open the dialog.
        asker.ask(rationaleBeforeAsk = false, rationaleAfterAnswer = true, granted = false)

        assertFalse(perm in asker.denied)
        assertEquals(setOf(perm), W3cPrefs.get(app).askedPermissions)
    }

    @Test
    fun `the first ask closed with Back is not blocked either`() {
        val asker = asker()
        asker.ask(rationaleBeforeAsk = false, rationaleAfterAnswer = false, granted = false)

        assertFalse(perm in asker.denied)
    }

    @Test
    fun `a second refusal with no rationale left is blocked`() {
        val asker = asker()
        asker.ask(rationaleBeforeAsk = false, rationaleAfterAnswer = true, granted = false)
        asker.ask(rationaleBeforeAsk = true, rationaleAfterAnswer = false, granted = false)

        assertTrue(perm in asker.denied)
    }

    @Test
    fun `a permission blocked before Setup opened is blocked at the first tap`() {
        // Refused twice in onboarding: it was asked for before, and now there is no rationale before or after.
        W3cPrefs.get(app).markPermissionsAsked(listOf(perm))
        val asker = asker()
        asker.ask(rationaleBeforeAsk = false, rationaleAfterAnswer = false, granted = false)

        assertTrue(perm in asker.denied)
    }

    @Test
    fun `a granted permission is never blocked`() {
        W3cPrefs.get(app).markPermissionsAsked(listOf(perm))
        val asker = asker()
        asker.ask(rationaleBeforeAsk = true, rationaleAfterAnswer = false, granted = true)

        assertFalse(perm in asker.denied)
    }

    @Test
    fun `the policy in one table`() {
        assertFalse(isPermanentlyDenied(true, rationaleBefore = true, rationaleAfter = false, askedBefore = true))
        assertFalse(isPermanentlyDenied(false, rationaleBefore = false, rationaleAfter = true, askedBefore = true))
        assertFalse(isPermanentlyDenied(false, rationaleBefore = false, rationaleAfter = false, askedBefore = false))
        assertTrue(isPermanentlyDenied(false, rationaleBefore = true, rationaleAfter = false, askedBefore = false))
        assertTrue(isPermanentlyDenied(false, rationaleBefore = false, rationaleAfter = false, askedBefore = true))
    }
}

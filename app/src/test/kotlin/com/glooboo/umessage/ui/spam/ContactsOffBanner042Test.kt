package com.glooboo.umessage.ui.spam

import android.Manifest
import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.spam.UnknownSenderMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** F4: without the Contacts permission the filter is paused; the banner says so. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class ContactsOffBanner042Test {

    @get:Rule val compose = createComposeRule()

    @Test fun policyWantsTheBannerOnlyWithoutContactsWhileTheFilterIsOn() {
        assertTrue(ContactsOffPolicy.needed(contactsGranted = false, mode = UnknownSenderMode.SMART))
        assertTrue(ContactsOffPolicy.needed(contactsGranted = false, mode = UnknownSenderMode.STRICT))
        assertFalse(ContactsOffPolicy.needed(contactsGranted = false, mode = UnknownSenderMode.OFF))
        assertFalse(ContactsOffPolicy.needed(contactsGranted = true, mode = UnknownSenderMode.SMART))
        assertFalse(ContactsOffPolicy.needed(contactsGranted = false, mode = null))
    }

    @Test fun bannerShowsTitleBodyAndAllowButtonWithoutThePermission() {
        compose.setContent { ContactsOffBanner(UnknownSenderMode.SMART) }

        compose.onNodeWithText("Spam filter is paused").assertIsDisplayed()
        compose.onNodeWithText("Allow Contacts so uMessage can tell friends from strangers.").assertIsDisplayed()
        compose.onNodeWithText("Allow").assertIsDisplayed()
    }

    @Test fun bannerIsGoneWhenContactsAreAllowed() {
        val app: Application = ApplicationProvider.getApplicationContext()
        shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS)

        compose.setContent { ContactsOffBanner(UnknownSenderMode.SMART) }

        compose.onNodeWithText("Spam filter is paused").assertDoesNotExist()
    }

    @Test fun bannerIsGoneWhenTheFilterIsOff() {
        compose.setContent { ContactsOffBanner(UnknownSenderMode.OFF) }

        compose.onNodeWithText("Spam filter is paused").assertDoesNotExist()
    }
}

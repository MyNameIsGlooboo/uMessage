package com.glooboo.umessage.ui.thread

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IncomingShareGuardTest {
    private val pkg = "com.glooboo.spamless"

    private fun allowed(uri: String) = IncomingShareGuard.isAllowed(Uri.parse(uri), pkg)

    @Test
    fun filesFromOtherProvidersAreAllowed() {
        assertThat(allowed("content://media/external/images/media/42")).isTrue()
        assertThat(allowed("content://com.android.providers.downloads.documents/document/7")).isTrue()
        assertThat(allowed("content://com.google.android.apps.photos.contentprovider/x/1")).isTrue()
    }

    @Test
    fun anAddressOfTheAppsOwnDatabaseIsRefused() {
        assertThat(allowed("file:///data/data/com.glooboo.spamless/databases/spamless.db")).isFalse()
        assertThat(allowed("file:///data/user/0/com.glooboo.spamless/shared_prefs/x.xml")).isFalse()
    }

    @Test
    fun theAppsOwnProvidersAreRefused() {
        assertThat(allowed("content://com.glooboo.spamless.provider/attachment_files/a.jpg")).isFalse()
        assertThat(allowed("content://com.glooboo.spamless/anything")).isFalse()
        assertThat(allowed("content://com.glooboo.spamless.debug.provider/x")).isFalse()
    }

    @Test
    fun missingAddressesAreRefused() {
        assertThat(IncomingShareGuard.isAllowed(null, pkg)).isFalse()
        assertThat(allowed("http://example.com/a.jpg")).isFalse()
    }
}

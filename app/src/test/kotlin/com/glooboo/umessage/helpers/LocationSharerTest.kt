package com.glooboo.umessage.helpers

import android.Manifest
import android.app.Application
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLooper
import java.util.Locale
import java.util.concurrent.TimeUnit

/** I52: the link text, and what happens without permission or without a fix. */
@RunWith(AndroidJUnit4::class)
class LocationSharerTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun theLinkUsesAsciiDigitsAndADotInAPersianLocale() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale("fa", "IR"))
            val text = LocationSharer.shareText(35.699739, 51.338097)
            assertThat(text).isEqualTo("https://maps.google.com/?q=35.69974,51.33810\ngeo:35.69974,51.33810")
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun coordinatesAreRoundedToFiveDecimalsAndKeepTheirSign() {
        assertThat(LocationSharer.coordinate(-33.8688197)).isEqualTo("-33.86882")
        assertThat(LocationSharer.coordinate(0.0)).isEqualTo("0.00000")
    }

    @Test
    fun theLinkStartsOnItsOwnLineAfterOtherText() {
        assertThat(LocationSharer.placedAfter("", "link")).isEqualTo("link")
        assertThat(LocationSharer.placedAfter("Meet here:\n", "link")).isEqualTo("link")
        assertThat(LocationSharer.placedAfter("Meet here:", "link")).isEqualTo("\nlink")
    }

    @Test
    fun withoutPermissionNothingIsAsked() {
        var result: LocationResult? = null
        LocationSharer(app).requestLink { result = it }
        assertThat(result).isEqualTo(LocationResult.NoPermission)
    }

    @Test
    fun withPermissionButLocationOffTheUserIsToldSo() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        val manager = app.getSystemService(LocationManager::class.java)
        shadowOf(manager).setProviderEnabled(LocationManager.NETWORK_PROVIDER, false)
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, false)
        var result: LocationResult? = null
        LocationSharer(app).requestLink { result = it }
        assertThat(result).isEqualTo(LocationResult.LocationOff)
    }

    @Test
    fun noFixWithinTheTimeoutGivesNoFix() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        val manager = app.getSystemService(LocationManager::class.java)
        shadowOf(manager).setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        var result: LocationResult? = null
        LocationSharer(app).requestLink { result = it }
        assertThat(result).isNull()

        ShadowLooper.idleMainLooper(LocationSharer.FIX_TIMEOUT_MS + 1, TimeUnit.MILLISECONDS)

        assertThat(result).isEqualTo(LocationResult.NoFix)
    }
}

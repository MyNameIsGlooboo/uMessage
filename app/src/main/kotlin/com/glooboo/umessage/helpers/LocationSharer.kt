package com.glooboo.umessage.helpers

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.util.Consumer
import java.util.Locale

/** What asking for the phone's position came to. */
sealed interface LocationResult {
    /** [text] is the link to put in the message field. */
    data class Found(val text: String) : LocationResult
    data object NoPermission : LocationResult
    data object LocationOff : LocationResult
    data object NoFix : LocationResult
}

/** One position fix on request: nothing is stored, nothing goes over the network, no updates keep running. */
class LocationSharer(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())

    fun hasPermission(): Boolean = LOCATION_PERMISSIONS.any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /** Asks for one fix (a coarse one is enough) and calls [onResult] on the main thread, within [FIX_TIMEOUT_MS]. */
    @SuppressLint("MissingPermission")
    fun requestLink(onResult: (LocationResult) -> Unit) {
        if (!hasPermission()) return onResult(LocationResult.NoPermission)
        val manager = context.getSystemService(LocationManager::class.java)
        val provider = manager?.let { providerFor(it) }
        if (manager == null || provider == null) return onResult(LocationResult.LocationOff)
        val cancel = CancellationSignal()
        var done = false
        val finish: (Location?) -> Unit = { location ->
            if (!done) {
                done = true
                val found = location?.let { LocationResult.Found(shareText(it.latitude, it.longitude)) }
                onResult(found ?: LocationResult.NoFix)
            }
        }
        handler.postDelayed({
            cancel.cancel()
            finish(null)
        }, FIX_TIMEOUT_MS)
        LocationManagerCompat.getCurrentLocation(
            manager, provider, cancel, ContextCompat.getMainExecutor(context), Consumer { finish(it) },
        )
    }

    /** The network provider gives a coarse fix; GPS is used only when the user allowed a precise one. */
    private fun providerFor(manager: LocationManager): String? {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        return listOfNotNull(
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER.takeIf { fine },
        ).firstOrNull { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
    }

    companion object {
        val LOCATION_PERMISSIONS = arrayOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        const val FIX_TIMEOUT_MS = 15_000L
        private const val DECIMALS = 5

        /** A latitude or longitude with a dot and ASCII digits, whatever the phone's language is. */
        fun coordinate(value: Double): String = String.format(Locale.US, "%.${DECIMALS}f", value)

        /** A web link every phone opens, then a geo: link for map apps (iPhones do not open geo: links). */
        fun shareText(latitude: Double, longitude: Double): String {
            val pair = "${coordinate(latitude)},${coordinate(longitude)}"
            return "https://maps.google.com/?q=$pair\ngeo:$pair"
        }

        /** [text] for a field that holds [before] before the cursor: the link starts on its own line. */
        fun placedAfter(before: CharSequence, text: String): String =
            if (before.isEmpty() || before.endsWith("\n")) text else "\n$text"
    }
}

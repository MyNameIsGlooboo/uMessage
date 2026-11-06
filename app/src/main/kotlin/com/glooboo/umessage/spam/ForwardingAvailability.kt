package com.glooboo.umessage.spam

import android.content.Context
import com.glooboo.umessage.R
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Whether automatic forwarding exists in this build. The Google Play build has none: no screens, no rules, nothing
 * sent, and a restored backup adds no forward rules. There is no setting that turns it on there. The flavor decides
 * through the `is_google_play_build` bool (true in `gplay`, false in `foss`).
 */
object ForwardingAvailability {
    /** True when this build has forwarding. */
    fun isAvailable(context: Context): Boolean =
        isAvailableFor(context.applicationContext.resources.getBoolean(R.bool.is_google_play_build))

    /** The rule itself: forwarding exists in every build except the Google Play build. */
    fun isAvailableFor(googlePlayBuild: Boolean): Boolean = !googlePlayBuild
}

/** The forward repository of a build without forwarding: no rules, and none can be added. */
object NoForwardRepository : ForwardRepository {
    override val rules: Flow<List<ForwardRule>> = flowOf(emptyList())

    override fun currentRules(): List<ForwardRule> = emptyList()

    override suspend fun add(rule: ForwardRule): Long = throw unavailable()
    override suspend fun update(rule: ForwardRule) = throw unavailable()
    override suspend fun setEnabled(id: Long, enabled: Boolean) = throw unavailable()
    override suspend fun remove(id: Long) = throw unavailable()

    private fun unavailable() = UnsupportedOperationException("Forwarding is not available in this build")
}

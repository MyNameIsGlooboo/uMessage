package com.glooboo.umessage.ui.main

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import com.glooboo.umessage.extensions.messagesDB
import com.glooboo.umessage.helpers.HiddenChats
import com.glooboo.umessage.ui.conversations.SimBadge
import com.glooboo.umessage.ui.conversations.ThreadSimCache
import com.glooboo.umessage.ui.spam.SimDirectory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A SIM tile of the search page; [label] is the nickname the user gave the SIM, else its carrier, else "SIM 2", and
 * the pad shows [slot] on a SIM-card icon.
 */
@Immutable
internal data class SimTile(val subscriptionId: Int, val label: String, val slot: Int)

/** The SIM tiles of the search page: one per SIM, and only on a phone with more than one active SIM. */
internal object SimFilter {

    const val MIN_SIMS = 2

    fun <T> tilesFor(
        sims: List<T>,
        subscriptionId: (T) -> Int,
        label: (T) -> String,
        slot: (T) -> Int = subscriptionId,
    ): List<SimTile> {
        if (sims.size < MIN_SIMS) return emptyList()
        val labels = withSlotsWhenAlike(sims.map { label(it) }, sims.map { slot(it) })
        return sims.mapIndexed { index, sim -> SimTile(subscriptionId(sim), labels[index], slot(sim)) }
    }

    /** Two SIMs with the same name (the same carrier) get their slot added: "Vodafone 1", "Vodafone 2". */
    fun withSlotsWhenAlike(labels: List<String>, slots: List<Int>): List<String> {
        val count = labels.groupingBy { it.lowercase() }.eachCount()
        return labels.mapIndexed { index, label ->
            if ((count[label.lowercase()] ?: 1) > 1) "$label ${slots[index]}" else label
        }
    }

    /**
     * The threads that have a message on [subscriptionId]: those the message cache holds for it, and those whose
     * newest message (in [newestSim], thread to subscription) used it.
     */
    fun threadsOnSim(stored: Collection<Long>, newestSim: Map<Long, Int>, subscriptionId: Int): Set<Long> {
        val threads = HashSet<Long>(stored)
        newestSim.forEach { (thread, sim) -> if (sim == subscriptionId) threads.add(thread) }
        return threads
    }

    /** Tapping the tile that is on turns it off. */
    fun afterTap(current: Int, tapped: Int): Int = if (current == tapped) NO_SIM else tapped

    suspend fun load(context: Context, subscriptionId: Int): Set<Long> = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val stored = runCatching { app.messagesDB.getThreadIdsOnSim(subscriptionId) }.getOrDefault(emptyList())
        val newest = runCatching { ThreadSimCache.get(app).load(app, MIN_SIMS) }.getOrDefault(emptyMap())
        val threads = threadsOnSim(stored, newest, subscriptionId)
        HiddenChats.get(app).withoutHidden(threads.toList()) { it }.toSet()
    }
}

/** The SIM filter's tile: on when [MainSearchState.filter] is [InboxFilter.Sim] and this is its SIM. */
internal fun MainSearchState.toggleSim(subscriptionId: Int) {
    if (filter == InboxFilter.Sim && simId == subscriptionId) {
        filter = InboxFilter.All
        simId = NO_SIM
    } else {
        simId = subscriptionId
        filter = InboxFilter.Sim
    }
}

/** The SIM tiles for this phone; empty until the SIMs are read, and with fewer than two SIMs. */
@Composable
internal fun rememberSimTiles(): List<SimTile> {
    val context = LocalContext.current
    val tiles by produceState(emptyList<SimTile>(), context) {
        value = withContext(Dispatchers.IO) {
            SimFilter.tilesFor(
                sims = runCatching { SimDirectory.load(context) }.getOrDefault(emptyList()),
                subscriptionId = { it.subscriptionId },
                label = { SimBadge.displayName(context, it) },
                slot = { it.slot },
            )
        }
    }
    return tiles
}

/** The SIM's threads for the filter that is on; null while another filter is on. */
@Composable
internal fun rememberSimThreads(search: MainSearchState): ShortcutHits? {
    val context = LocalContext.current
    val on = search.filter == InboxFilter.Sim
    val id = search.simId
    val hits by produceState<ShortcutHits?>(null, on, id) {
        value = if (on) ShortcutHits(InboxFilter.Sim, SimFilter.load(context, id), loaded = true) else null
    }
    return if (on) hits ?: ShortcutHits(InboxFilter.Sim) else null
}

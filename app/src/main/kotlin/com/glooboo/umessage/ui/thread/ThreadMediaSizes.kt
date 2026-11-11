package com.glooboo.umessage.ui.thread

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.glooboo.umessage.models.Message
import java.util.concurrent.ConcurrentHashMap

/**
 * Width-to-height ratios of message photos and videos seen before, so a bubble is drawn at its final size before the
 * picture has loaded. TU-25: every bubble started as a 4:3 box and changed size once the picture came in, which made
 * the chat jump while scrolling. A ratio is learnt the first time a picture loads and kept on disk, keyed by its uri
 * (MMS part uris do not change).
 */
object MediaSizes {
    /** Ratio for a picture never seen before. */
    const val DEFAULT_ASPECT = 4f / 3f

    private const val PREFS = "media_sizes"
    private const val MAX_ENTRIES = 4000

    private val memory = ConcurrentHashMap<String, Float>()
    private val warmed = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var prefs: SharedPreferences? = null

    /**
     * Keys on disk in the order they were learnt, read once (SharedPreferences.getAll copies the whole map) and then
     * kept by [store]. Keys that were already saved before this process started come first, in unspecified order.
     */
    private var savedKeys: LinkedHashSet<String>? = null

    private fun prefs(context: Context): SharedPreferences =
        prefs ?: context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).also { prefs = it }

    /** The ratio already in memory, or null. Safe on the main thread. */
    fun cached(key: String): Float? = memory[key]

    /**
     * Reads the saved ratios of every picture in [messages] into memory. Call off the main thread while the chat
     * loads, before its rows are shown.
     */
    fun warm(context: Context, messages: List<Message>) {
        val keys = messages.asSequence()
            .mapNotNull { it.attachment?.attachments }
            .flatten()
            .filter { it.mimetype.startsWith("image/") || it.mimetype.startsWith("video/") }
            .map { it.uriString }
            .filter { it !in warmed }
            .toList()
        if (keys.isEmpty()) return
        val saved = prefs(context)
        keys.forEach { key ->
            warmed += key
            val aspect = saved.getFloat(key, 0f)
            if (aspect > 0f) memory[key] = aspect
        }
    }

    /** Remembers the size of a loaded picture, in memory at once and on disk in the background. */
    fun store(context: Context, key: String, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val aspect = width.toFloat() / height
        if (memory[key] == aspect) return
        memory[key] = aspect
        val saved = prefs(context)
        synchronized(this) {
            val keys = savedKeys ?: LinkedHashSet(saved.all.keys).also { savedKeys = it }
            val isNew = keys.add(key)
            val evicted = if (isNew) evictionBatch(keys, MAX_ENTRIES) else emptyList()
            saved.edit {
                // Forget the oldest tenth when full: that only means one more jump for those pictures.
                evicted.forEach { remove(it) }
                putFloat(key, aspect)
            }
            keys.removeAll(evicted.toSet())
        }
    }

    /** Forgets the in-memory copy, as after the process was killed; the disk copy stays. For tests. */
    internal fun clearMemory() {
        memory.clear()
        warmed.clear()
        synchronized(this) { savedKeys = null }
    }
}

private const val EVICT_FRACTION = 10

/**
 * The oldest keys to drop once [keys] (oldest first) has grown past [max]: enough to leave a tenth of [max] free, so
 * eviction does not run for every new picture. Empty while within the bound.
 */
internal fun evictionBatch(keys: Collection<String>, max: Int): List<String> {
    if (keys.size <= max) return emptyList()
    val drop = keys.size - max + (max / EVICT_FRACTION).coerceAtLeast(1)
    return keys.take(drop.coerceAtMost(keys.size - 1))
}

/** The ratio a media bubble is drawn at: the loaded picture's, else the one learnt earlier, else 4:3. */
internal fun mediaAspect(loadedWidth: Int?, loadedHeight: Int?, cached: Float?): Float =
    if (loadedWidth != null && loadedHeight != null && loadedWidth > 0 && loadedHeight > 0) {
        loadedWidth.toFloat() / loadedHeight
    } else {
        cached ?: MediaSizes.DEFAULT_ASPECT
    }

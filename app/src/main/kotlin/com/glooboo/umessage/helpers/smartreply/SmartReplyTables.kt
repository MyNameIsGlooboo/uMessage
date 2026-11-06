package com.glooboo.umessage.helpers.smartreply

import android.content.Context
import com.glooboo.umessage.helpers.smartreply.engine.EngineTables
import com.glooboo.umessage.helpers.smartreply.engine.SmartEngine
import kotlinx.serialization.json.Json
import java.io.IOException

/**
 * Loads the phrase tables of `assets/smart_replies/` (one `<language>.json` per language) once and builds the matcher.
 * A language is added by dropping a file there; a file that cannot be read is skipped, never a crash.
 */
object SmartReplyTables {

    private const val DIRECTORY = "smart_replies"
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var matcher: SmartReplyMatcher? = null

    fun matcher(context: Context): SmartReplyMatcher =
        matcher ?: synchronized(this) { matcher ?: load(context).also { matcher = it } }

    fun parse(text: String): SmartReplyTable = json.decodeFromString(text)

    private fun load(context: Context): SmartReplyMatcher {
        val assets = context.applicationContext.assets
        val files = try {
            assets.list(DIRECTORY).orEmpty().filter { it.endsWith(".json") }
        } catch (_: IOException) {
            emptyList()
        }
        val tables = files.mapNotNull { name ->
            try {
                assets.open("$DIRECTORY/$name").use { parse(it.readBytes().toString(Charsets.UTF_8)) }
            } catch (_: IOException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }
        return SmartReplyMatcher(tables, SmartEngine(EngineTables.load(assets)))
    }
}

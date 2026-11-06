package com.glooboo.umessage.helpers.smartreply.engine

import android.content.res.AssetManager
import kotlinx.serialization.json.Json
import java.io.IOException

/**
 * Reads the tables of `assets/smart_engine/` (one `<language>.json` per language). A file that cannot be read, is not
 * valid, or whose `language` differs from its file name is skipped, never a crash; a missing folder gives no tables.
 */
object EngineTables {

    private const val DIRECTORY = "smart_engine"
    private const val EXTENSION = ".json"
    private const val BOM = "\uFEFF"
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): EngineTable = json.decodeFromString(text.removePrefix(BOM))

    /** Parses `(file name, content)` pairs; an invalid pair is left out. */
    fun parseAll(files: List<Pair<String, String>>): List<EngineTable> = files.mapNotNull { (name, content) ->
        try {
            parse(content).takeIf { it.language == name.removeSuffix(EXTENSION) }
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun load(assets: AssetManager): List<EngineTable> {
        val names = try {
            assets.list(DIRECTORY).orEmpty().filter { it.endsWith(EXTENSION) }
        } catch (_: IOException) {
            emptyList()
        }
        val files = names.mapNotNull { name ->
            try {
                name to assets.open("$DIRECTORY/$name").use { it.readBytes().toString(Charsets.UTF_8) }
            } catch (_: IOException) {
                null
            }
        }
        return parseAll(files)
    }
}

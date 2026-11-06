package com.glooboo.umessage.backup

import com.glooboo.umessage.backup.SettingsBackupFormat.MAX_NOTE
import com.glooboo.umessage.backup.SettingsBackupFormat.MAX_PATTERN
import com.glooboo.umessage.backup.SettingsBackupFormat.MAX_TARGET
import com.glooboo.umessage.spam.ForwardRule
import com.glooboo.umessage.spam.ForwardSim
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.RegexGuard
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamRule
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * One entry of a settings backup to and from JSON (see [SettingsBackupFormat] for the file). Reading is tolerant: an
 * entry with an unknown type or no usable value gives null, and values of the wrong type or out of range are missing.
 */
internal object BackupJson {
    private const val MINUTES_PER_DAY = 24 * 60

    fun rule(r: SpamRule) = buildJsonObject {
        put("kind", r.kind.name)
        put("type", r.type.name)
        put("pattern", r.pattern)
        put("note", r.note)
        put("createdAt", r.createdAt)
    }

    fun forward(r: ForwardRule) = buildJsonObject {
        put("senderType", r.senderType?.name)
        put("senderPattern", r.senderPattern)
        put("bodyType", r.bodyType?.name)
        put("bodyPattern", r.bodyPattern)
        put("windowStart", r.windowStart)
        put("windowEnd", r.windowEnd)
        put("targets", buildJsonArray { r.targets.forEach { add(JsonPrimitive(it)) } })
        put("sendFrom", r.sendFrom.name)
        put("includeCodes", r.includeCodes)
        put("enabled", r.enabled)
        put("note", r.note)
        put("createdAt", r.createdAt)
    }

    fun settings(s: BackupSettings) = buildJsonObject {
        for ((key, value) in s.values()) {
            when (value) {
                is Enum<*> -> put(key, value.name)
                is Boolean -> put(key, value)
                is Int -> put(key, value)
                is Long -> put(key, value)
                is String -> put(key, value)
                else -> Unit
            }
        }
    }

    fun readRule(element: JsonElement): SpamRule? {
        val o = element as? JsonObject ?: return null
        val kind = enumOf<ListKind>(o.str("kind")) ?: return null
        val type = enumOf<RuleType>(o.str("type")) ?: return null
        val pattern = o.str("pattern")?.trim().orEmpty()
        if (pattern.isEmpty() || pattern.length > MAX_PATTERN) return null
        if (type == RuleType.REGEX && RegexGuard.validate(pattern) != null) return null
        return SpamRule(
            kind = kind,
            type = type,
            pattern = pattern,
            note = o.str("note").orEmpty().take(MAX_NOTE),
            createdAt = o.long("createdAt") ?: System.currentTimeMillis(),
        )
    }

    fun readForward(element: JsonElement): ForwardRule? {
        val o = element as? JsonObject ?: return null
        val sender = readCondition(o, "senderType", "senderPattern", ForwardRule.SENDER_TYPES)
        val body = readCondition(o, "bodyType", "bodyPattern", ForwardRule.BODY_TYPES)
        val badRegex = body?.first == RuleType.REGEX && RegexGuard.validate(body.second) != null
        val targets = readTargets(o)
        val usable = (sender != null || body != null) && !badRegex
        if (!usable || targets.isEmpty()) return null
        val window = readWindow(o)
        return ForwardRule(
            senderType = sender?.first,
            senderPattern = sender?.second.orEmpty(),
            bodyType = body?.first,
            bodyPattern = body?.second.orEmpty(),
            windowStart = window?.first,
            windowEnd = window?.second,
            subscriptionId = null,
            targets = targets,
            sendFrom = enumOf<ForwardSim>(o.str("sendFrom")) ?: ForwardSim.SAME,
            includeCodes = o.bool("includeCodes") ?: false,
            enabled = o.bool("enabled") ?: true,
            note = o.str("note").orEmpty().take(MAX_NOTE),
            createdAt = o.long("createdAt") ?: System.currentTimeMillis(),
        )
    }

    /** A sender or body condition: a type allowed there and a non-blank pattern, or null. */
    private fun readCondition(
        o: JsonObject,
        typeKey: String,
        patternKey: String,
        allowed: Collection<RuleType>,
    ): Pair<RuleType, String>? {
        val type = enumOf<RuleType>(o.str(typeKey))?.takeIf { it in allowed } ?: return null
        val pattern = o.str(patternKey)?.trim().orEmpty().take(MAX_PATTERN)
        return if (pattern.isEmpty()) null else type to pattern
    }

    /** Non-blank text targets of sane length, each once, at most [ForwardRule.MAX_TARGETS]. */
    private fun readTargets(o: JsonObject): List<String> = o.array("targets").orEmpty()
        .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.trim() }
        .filter { it.isNotEmpty() && it.length <= MAX_TARGET }
        .distinct().take(ForwardRule.MAX_TARGETS)

    /** Start and end minutes of the time window, or null when either is missing or not a time of day. */
    private fun readWindow(o: JsonObject): Pair<Int, Int>? {
        val start = o.int("windowStart") ?: return null
        val end = o.int("windowEnd") ?: return null
        val day = 0 until MINUTES_PER_DAY
        return if (start in day && end in day) start to end else null
    }
}

private const val MAX_PRESET_NAME = 40

internal fun isPresetName(name: String) =
    name.length <= MAX_PRESET_NAME && name.all { it.isLetter() || it == '_' || it == '/' }

internal inline fun <reified T : Enum<T>> enumOf(name: String?): T? =
    if (name == null) null else enumValues<T>().firstOrNull { it.name == name }

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

internal fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray

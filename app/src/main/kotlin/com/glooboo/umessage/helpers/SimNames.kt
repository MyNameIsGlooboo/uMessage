package com.glooboo.umessage.helpers

import org.json.JSONException
import org.json.JSONObject

/** What the user typed for one SIM: a short [nickname] and the [number], both optional. */
data class SimName(val nickname: String, val number: String)

/**
 * Nicknames and numbers the user gave their SIMs (I34), kept on the phone as typed. A SIM is found by its slot and
 * carrier name, not by its subscription id, which changes after a SIM swap; a SIM that is not found simply shows its
 * carrier name. Android 13 and newer hide the phone's own number without a permission uMessage does not ask for, which
 * is why the number is typed.
 */
class SimNames(private val prefs: ComposerPrefs) {

    fun get(slot: Int, carrier: String): SimName? = parse(prefs.simNamesJson)[key(slot, carrier)]

    /** Saves what was typed for the SIM; two blank fields forget the entry. */
    fun set(slot: Int, carrier: String, nickname: String, number: String) {
        val all = parse(prefs.simNamesJson).toMutableMap()
        val name = SimName(nickname.trim().take(MAX_NICKNAME_LENGTH), number.trim().take(MAX_NUMBER_LENGTH))
        if (name.nickname.isEmpty() && name.number.isEmpty()) {
            all.remove(key(slot, carrier))
        } else {
            all[key(slot, carrier)] = name
        }
        prefs.simNamesJson = toJson(all)
    }

    /** The SIM's name in the composer: nickname (else [carrierLabel]), then the typed number (else the carrier's). */
    fun label(slot: Int, carrier: String, carrierLabel: String, carrierNumber: String?): String =
        labelOf(get(slot, carrier), carrierLabel, carrierNumber)

    companion object {
        const val MAX_NICKNAME_LENGTH = 20
        const val MAX_NUMBER_LENGTH = 20
        private const val KEY_NICKNAME = "nickname"
        private const val KEY_NUMBER = "number"

        fun key(slot: Int, carrier: String) = "$slot|${carrier.trim()}"

        fun labelOf(name: SimName?, carrierLabel: String, carrierNumber: String?): String {
            val base = name?.nickname?.ifEmpty { null } ?: carrierLabel
            val number = name?.number?.ifEmpty { null } ?: carrierNumber?.ifEmpty { null }
            return if (number == null) base else "$base ($number)"
        }

        fun parse(json: String): Map<String, SimName> = try {
            val root = JSONObject(json)
            root.keys().asSequence().mapNotNull { key ->
                val item = root.optJSONObject(key) ?: return@mapNotNull null
                key to SimName(item.optString(KEY_NICKNAME), item.optString(KEY_NUMBER))
            }.toMap()
        } catch (_: JSONException) {
            emptyMap()
        }

        fun toJson(all: Map<String, SimName>): String = JSONObject().apply {
            all.forEach { (key, name) ->
                put(key, JSONObject().put(KEY_NICKNAME, name.nickname).put(KEY_NUMBER, name.number))
            }
        }.toString()
    }
}

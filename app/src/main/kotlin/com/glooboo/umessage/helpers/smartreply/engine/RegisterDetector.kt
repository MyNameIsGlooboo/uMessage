package com.glooboo.umessage.helpers.smartreply.engine

/** How the sender addresses the user. [UNKNOWN] (no marker, or both kinds) means the base pools are used. */
internal enum class Register { FORMAL, INFORMAL, UNKNOWN }

/** Reads the `register` markers of a table in the incoming text. Pure JVM. */
internal object RegisterDetector {

    fun detect(index: EngineIndex, reading: EngineReading): Register {
        val formal = index.formalMarkers.any(reading::has)
        val informal = index.informalMarkers.any(reading::has)
        return when {
            formal && !informal -> Register.FORMAL
            informal && !formal -> Register.INFORMAL
            else -> Register.UNKNOWN
        }
    }
}

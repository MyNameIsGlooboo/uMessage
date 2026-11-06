package com.glooboo.umessage.helpers.smartreply.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Formal, informal or unknown sender: the formal pools only replace the base pools for a formal sender. */
class RegisterDetectorTest {

    private val table = EngineTestData.fixtureTable()
    private val engine = EngineTestData.fixture()

    private fun register(text: String): Register =
        RegisterDetector.detect(EngineIndex(table), EngineReading.of(text)!!)

    @Test
    fun markersDecideTheRegister() {
        assertThat(register("Would you like tea?")).isEqualTo(Register.FORMAL)
        assertThat(register("Do you wanna come?")).isEqualTo(Register.INFORMAL)
        assertThat(register("Are you home?")).isEqualTo(Register.UNKNOWN)
    }

    @Test
    fun bothKindsOfMarkerMeanUnknown() {
        assertThat(register("Would you wanna come?")).isEqualTo(Register.UNKNOWN)
    }

    @Test
    fun aFormalSenderGetsTheFormalPools() {
        val reply = engine.reply("Would you like some tea?", "en")!!
        assertThat(reply.replies)
            .containsExactly("Yes, certainly", "I am afraid not", "Let me check and get back to you")
            .inOrder()
    }

    @Test
    fun anInformalOrUnknownSenderGetsTheBasePools() {
        val unknown = engine.reply("Are you home?", "en", 0L)!!
        assertThat(unknown.replies).containsExactly("Yes", "No", "Let me check").inOrder()
        val informal = engine.reply("Do you wanna come?", "en", 0L)!!
        assertThat(informal.replies).containsExactly("Yes", "No", "Let me check").inOrder()
    }

    @Test
    fun aFormalPoolIsOnlyUsedForTheGroupsItHas() {
        val reply = engine.reply("Would you kindly send me the file?", "en", 0L)!!
        assertThat(reply.intentId).isEqualTo("engine_request")
        assertThat(reply.replies).containsExactly("Sure", "OK, will do", "Give me a minute").inOrder()
    }

    @Test
    fun aFormalPoolTooSmallForTheTypeIsIgnored() {
        val small = EngineTestData.fixtureTable().copy(formal = mapOf("when" to listOf("Tomorrow, if convenient")))
        val reply = SmartEngine(listOf(small)).reply("Would you tell me what time is dinner?", "en", 0L)!!
        assertThat(reply.intentId).isEqualTo("engine_when")
        assertThat(reply.replies).hasSize(3)
        assertThat(reply.replies).doesNotContain("Tomorrow, if convenient")
    }
}

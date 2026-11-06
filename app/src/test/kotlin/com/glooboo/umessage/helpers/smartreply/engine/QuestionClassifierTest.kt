package com.glooboo.umessage.helpers.smartreply.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Rules 3 and 4: what counts as a question and the order the types are tried in. Invented texts only. */
class QuestionClassifierTest {

    private val engine = EngineTestData.fixture()

    private fun type(text: String) = engine.reply(text, "en")?.intentId

    @Test
    fun eachTypeIsRecognised() {
        assertThat(type("Are you home?")).isEqualTo("engine_yes_no")
        assertThat(type("What time are we eating?")).isEqualTo("engine_when")
        assertThat(type("Where is the key?")).isEqualTo("engine_where")
        assertThat(type("Shall we go for a walk?")).isEqualTo("engine_invite")
        assertThat(type("Please send the file?")).isEqualTo("engine_request")
        assertThat(type("Do you want tea or coffee?")).isEqualTo("engine_choice")
    }

    @Test
    fun whenBeatsWhereAndInvite() {
        assertThat(type("Where is it and when?")).isEqualTo("engine_when")
        assertThat(type("When shall we go?")).isEqualTo("engine_when")
    }

    @Test
    fun choiceBeatsEverythingAndWhereBeatsInvite() {
        assertThat(type("When shall we go today or tomorrow?")).isEqualTo("engine_choice")
        assertThat(type("When shall we go, today or tomorrow?")).isNull()
        assertThat(type("Where is it, shall we go?")).isEqualTo("engine_where")
    }

    @Test
    fun inviteAndRequestBeatYesNo() {
        assertThat(type("Do you want to come?")).isEqualTo("engine_invite")
        assertThat(type("Can you send me the file?")).isEqualTo("engine_request")
    }

    @Test
    fun anEndingMakesAYesNoOnlyWithAQuestionMark() {
        assertThat(type("You are home, right?")).isEqualTo("engine_yes_no")
        assertThat(type("You are home right")).isNull()
    }

    @Test
    fun aStarterMakesAQuestionWithoutAMark() {
        assertThat(type("Do you have my umbrella")).isEqualTo("engine_yes_no")
    }

    @Test
    fun noQuestionMarkAndNoStarterGivesNothing() {
        assertThat(type("When are you coming")).isNull()
        assertThat(type("Shall we go")).isNull()
        assertThat(type("Tomorrow works")).isNull()
        assertThat(type("Send me the file")).isNull()
    }

    @Test
    fun aQuestionMarkWithoutAnyCueGivesNothing() {
        assertThat(type("Why?")).isNull()
        assertThat(type("How much is it?")).isNull()
        assertThat(type("Who is that?")).isNull()
    }
}

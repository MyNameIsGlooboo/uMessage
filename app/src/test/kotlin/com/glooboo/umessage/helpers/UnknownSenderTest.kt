package com.glooboo.umessage.helpers

import com.glooboo.umessage.spam.engine.ContactLookup
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UnknownSenderTest {

    @Test fun notAContactAndNeverWrittenToIsUnknown() {
        assertThat(isUnknownSender(ContactLookup.NOT_FOUND, ContactLookup.NOT_FOUND)).isTrue()
    }

    @Test fun aContactIsKnown() {
        assertThat(isUnknownSender(ContactLookup.FOUND, ContactLookup.NOT_FOUND)).isFalse()
    }

    @Test fun someoneYouWroteToIsKnown() {
        assertThat(isUnknownSender(ContactLookup.NOT_FOUND, ContactLookup.FOUND)).isFalse()
    }

    @Test fun aLookupThatCouldNotAnswerNeverMakesASenderUnknown() {
        assertThat(isUnknownSender(ContactLookup.UNKNOWN, ContactLookup.NOT_FOUND)).isFalse()
        assertThat(isUnknownSender(ContactLookup.NOT_FOUND, ContactLookup.UNKNOWN)).isFalse()
    }

    @Test fun aGroupHasNoSingleSender() {
        assertThat(isUnknownSender(ContactLookup.NOT_FOUND, ContactLookup.NOT_FOUND, isGroup = true)).isFalse()
    }
}

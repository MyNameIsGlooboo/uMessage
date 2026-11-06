package com.glooboo.umessage.helpers

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** I34: SIM nicknames and numbers, found by slot and carrier rather than by subscription id. */
@RunWith(AndroidJUnit4::class)
class SimNamesTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var names: SimNames

    @Before
    fun setUp() {
        app.composerPrefs.simNamesJson = ""
        names = SimNames(app.composerPrefs)
    }

    @Test
    fun nicknameWinsOverTheCarrierNameAndTheTypedNumberOverTheCarrierNumber() {
        names.set(slot = 1, carrier = "Carrier A", nickname = "Work", number = "+10000000001")
        assertThat(names.label(1, "Carrier A", "Carrier A", "+19999999999")).isEqualTo("Work (+10000000001)")
        assertThat(names.label(2, "Carrier B", "Carrier B", "+19999999999")).isEqualTo("Carrier B (+19999999999)")
        assertThat(names.label(2, "Carrier B", "Carrier B", null)).isEqualTo("Carrier B")
    }

    @Test
    fun aBlankNicknameFallsBackToTheCarrierName() {
        names.set(slot = 1, carrier = "Carrier A", nickname = "  ", number = "+10000000001")
        assertThat(names.label(1, "Carrier A", "Carrier A", null)).isEqualTo("Carrier A (+10000000001)")
    }

    @Test
    fun theNameDoesNotDependOnTheSubscriptionIdButASwappedSimFallsBack() {
        names.set(slot = 2, carrier = "Carrier B", nickname = "Home", number = "")
        // The same SIM comes back with another subscription id: the key has none, so the name is found again.
        assertThat(names.label(2, "Carrier B", "Carrier B", null)).isEqualTo("Home")
        // A different carrier in that slot (a SIM swap) shows its own name.
        assertThat(names.label(2, "Carrier C", "Carrier C", null)).isEqualTo("Carrier C")
    }

    @Test
    fun blankFieldsForgetTheEntryAndCorruptDataReadsAsEmpty() {
        names.set(1, "Carrier A", "Work", "")
        names.set(1, "Carrier A", "", " ")
        assertThat(names.get(1, "Carrier A")).isNull()
        app.composerPrefs.simNamesJson = "][ broken"
        assertThat(names.get(1, "Carrier A")).isNull()
    }
}

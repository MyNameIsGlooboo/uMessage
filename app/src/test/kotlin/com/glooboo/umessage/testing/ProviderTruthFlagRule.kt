package com.glooboo.umessage.testing

import com.glooboo.umessage.helpers.ProviderTruthFlag
import org.junit.rules.ExternalResource

/**
 * N-5: sets [ProviderTruthFlag.enabled] to [enabled] for each test and puts back the value it found afterwards, also
 * when the test fails, so one test can never leave the new path switched on for the next.
 *
 * ```
 * @get:Rule val flag = ProviderTruthFlagRule(enabled = true)
 * ```
 *
 * A test can still switch the flag inside its body (for example to run both paths); the rule restores it.
 */
class ProviderTruthFlagRule(private val enabled: Boolean) : ExternalResource() {
    private var before = ProviderTruthFlag.SHIPPED_DEFAULT

    override fun before() {
        before = ProviderTruthFlag.enabled
        ProviderTruthFlag.enabled = enabled
    }

    override fun after() {
        ProviderTruthFlag.enabled = before
    }
}

package org.autojs.autojs.guardian

import org.junit.Assert.assertEquals
import org.junit.Test

class ScriptGuardianPreferenceStateTest {
    @Test
    fun enabledConfigIsAppliedOnResumeWithoutPreStopping() {
        val config = config(enabled = true, path = "receiver.js")
        val state = ScriptGuardianPreferenceState(initiallyEnabled = true)

        assertEquals(ScriptGuardianPreferenceAction.Apply(config), state.transition(config))
        assertEquals(ScriptGuardianPreferenceAction.Apply(config), state.transition(config))
    }

    @Test
    fun enabledPathChangeAppliesLatestConfig() {
        val first = config(enabled = true, path = "receiver.js")
        val second = config(enabled = true, path = "replacement.js")
        val state = ScriptGuardianPreferenceState(initiallyEnabled = true)

        assertEquals(ScriptGuardianPreferenceAction.Apply(first), state.transition(first))
        assertEquals(ScriptGuardianPreferenceAction.Apply(second), state.transition(second))
    }

    @Test
    fun disablingStopsOnceAfterEnabledState() {
        val disabled = config(enabled = false, path = "receiver.js")
        val state = ScriptGuardianPreferenceState(initiallyEnabled = true)

        assertEquals(ScriptGuardianPreferenceAction.Stop, state.transition(disabled))
        assertEquals(ScriptGuardianPreferenceAction.None, state.transition(disabled))
    }

    @Test
    fun initiallyDisabledStateDoesNotStopService() {
        val disabled = config(enabled = false, path = "receiver.js")
        val state = ScriptGuardianPreferenceState(initiallyEnabled = false)

        assertEquals(ScriptGuardianPreferenceAction.None, state.transition(disabled))
    }

    private fun config(enabled: Boolean, path: String) = ScriptGuardianConfig(
        enabled = enabled,
        relativePath = path,
        scriptRoot = "/tmp"
    )
}

package org.autojs.autojs.guardian

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptGuardianStartupRestorerTest {
    @Test
    fun enabledConfigRecordsMainProcessStartBeforeApplying() {
        val config = config(enabled = true)
        val events = mutableListOf<String>()
        var applied: ScriptGuardianConfig? = null
        val restorer = ScriptGuardianStartupRestorer(
            loadConfig = { config },
            recordRestore = { events += "record" },
            applyConfig = {
                events += "apply"
                applied = it
            }
        )

        assertTrue(restorer.restoreIfEnabled())
        assertEquals(listOf("record", "apply"), events)
        assertSame(config, applied)
    }

    @Test
    fun disabledConfigDoesNotRecordOrStartService() {
        var callbacks = 0
        val restorer = ScriptGuardianStartupRestorer(
            loadConfig = { config(enabled = false) },
            recordRestore = { callbacks += 1 },
            applyConfig = { callbacks += 1 }
        )

        assertFalse(restorer.restoreIfEnabled())
        assertEquals(0, callbacks)
    }

    private fun config(enabled: Boolean) = ScriptGuardianConfig(
        enabled = enabled,
        relativePath = "agent.js",
        scriptRoot = "/scripts"
    )
}

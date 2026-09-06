package org.autojs.autojs.mcp

import org.junit.Assert.assertEquals
import org.junit.Test

class McpServiceStartControllerTest {
    @Test
    fun failedBackgroundStartIsRetriedWhenAppReturnsToForeground() {
        val startResults = ArrayDeque(listOf(false, true))
        var startCount = 0
        val controller = McpServiceStartController(
            startService = {
                startCount += 1
                startResults.removeFirst()
            },
            stopService = {}
        )

        controller.apply(enabled = true)
        controller.onForeground(enabled = true)
        controller.onForeground(enabled = true)

        assertEquals(2, startCount)
    }

    @Test
    fun successfulStartDoesNotRetryOnForeground() {
        var startCount = 0
        val controller = McpServiceStartController(
            startService = {
                startCount += 1
                true
            },
            stopService = {}
        )

        controller.apply(enabled = true)
        controller.onForeground(enabled = true)

        assertEquals(1, startCount)
    }

    @Test
    fun disablingMcpClearsPendingRetryAndStopsService() {
        var startCount = 0
        var stopCount = 0
        val controller = McpServiceStartController(
            startService = {
                startCount += 1
                false
            },
            stopService = { stopCount += 1 }
        )

        controller.apply(enabled = true)
        controller.apply(enabled = false)
        controller.onForeground(enabled = false)

        assertEquals(1, startCount)
        assertEquals(1, stopCount)
    }
}

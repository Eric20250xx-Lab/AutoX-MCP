package org.autojs.autoxjs.mcp

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpSelfHealthSupervisorTest {
    private var elapsed = 1_000L
    private var wall = 50_000L

    private fun supervisor() = McpSelfHealthSupervisor(
        monotonicNowMillis = { elapsed },
        wallNowMillis = { wall },
        processEpoch = 42L
    )

    @Test
    fun twoConsecutiveFailuresRequestEngineRestart() {
        val supervisor = supervisor()
        supervisor.startSession()
        val firstEngine = supervisor.beginEngineStart()
        supervisor.engineStartSucceeded(firstEngine)

        assertEquals(
            McpRecoveryAction.NONE,
            supervisor.recordProbeFailure(firstEngine, McpSelfHealthSupervisor.ERROR_PROBE_TIMEOUT)
        )
        assertEquals(McpHealthState.DEGRADED, supervisor.snapshot().state)
        assertEquals(1, supervisor.snapshot().consecutiveFailures)

        assertEquals(
            McpRecoveryAction.RESTART_ENGINE,
            supervisor.recordProbeFailure(firstEngine, McpSelfHealthSupervisor.ERROR_PROBE_TIMEOUT)
        )
        assertEquals(McpHealthState.RECOVERING, supervisor.snapshot().state)

        wall += 10
        val replacement = supervisor.beginEngineStart()
        supervisor.engineStartSucceeded(replacement)
        assertEquals(2L, replacement.generation)
        assertEquals(0, supervisor.snapshot().consecutiveFailures)
        assertEquals(McpHealthState.RECOVERING, supervisor.snapshot().state)
    }

    @Test
    fun twoSuccessfulProbesClearTransientFailure() {
        val supervisor = supervisor()
        supervisor.startSession()
        val identity = supervisor.beginEngineStart()
        supervisor.engineStartSucceeded(identity)
        supervisor.recordProbeFailure(identity, McpSelfHealthSupervisor.ERROR_PROBE_IO)

        elapsed += 250
        supervisor.recordProbeSuccess(identity)

        val recovering = supervisor.snapshot()
        assertEquals(McpHealthState.RECOVERING, recovering.state)
        assertEquals(0, recovering.consecutiveFailures)
        assertEquals(McpSelfHealthSupervisor.ERROR_PROBE_IO, recovering.lastErrorCode)

        elapsed += 250
        supervisor.recordProbeSuccess(identity)

        val snapshot = supervisor.snapshot()
        assertEquals(McpHealthState.HEALTHY, snapshot.state)
        assertEquals(0, snapshot.consecutiveFailures)
        assertNull(snapshot.lastErrorCode)
        assertEquals(0L, snapshot.lastProbeAgeMillis)
    }

    @Test
    fun staleProbeResultCannotDegradeReplacementEngine() {
        val supervisor = supervisor()
        supervisor.startSession()
        val firstEngine = supervisor.beginEngineStart()
        supervisor.engineStartSucceeded(firstEngine)
        val replacement = supervisor.beginEngineStart()
        supervisor.engineStartSucceeded(replacement)

        assertEquals(
            McpRecoveryAction.NONE,
            supervisor.recordProbeFailure(firstEngine, McpSelfHealthSupervisor.ERROR_PROBE_TIMEOUT)
        )

        val snapshot = supervisor.snapshot()
        assertEquals(McpHealthState.HEALTHY, snapshot.state)
        assertEquals(0, snapshot.consecutiveFailures)
        assertNull(snapshot.lastErrorCode)
    }

    @Test
    fun fourthRestartInsideTenMinutesIsRateLimitedButLaterSuccessRecovers() {
        val supervisor = supervisor()
        supervisor.startSession()
        var identity = supervisor.beginEngineStart()
        supervisor.engineStartSucceeded(identity)

        repeat(3) {
            assertEquals(
                McpRecoveryAction.NONE,
                supervisor.recordProbeFailure(identity, McpSelfHealthSupervisor.ERROR_PROBE_CONNECT)
            )
            assertEquals(
                McpRecoveryAction.RESTART_ENGINE,
                supervisor.recordProbeFailure(identity, McpSelfHealthSupervisor.ERROR_PROBE_CONNECT)
            )
            wall += 1
            identity = supervisor.beginEngineStart()
            supervisor.engineStartSucceeded(identity)
            elapsed += 1_000
        }

        supervisor.recordProbeFailure(identity, McpSelfHealthSupervisor.ERROR_PROBE_CONNECT)
        assertEquals(
            McpRecoveryAction.RATE_LIMITED,
            supervisor.recordProbeFailure(identity, McpSelfHealthSupervisor.ERROR_PROBE_CONNECT)
        )
        assertEquals(McpHealthState.FAILED, supervisor.snapshot().state)
        assertEquals(
            McpSelfHealthSupervisor.ERROR_RESTART_RATE_LIMITED,
            supervisor.snapshot().lastErrorCode
        )

        supervisor.recordProbeSuccess(identity)
        assertEquals(McpHealthState.RECOVERING, supervisor.snapshot().state)
        assertEquals(
            McpSelfHealthSupervisor.ERROR_RESTART_RATE_LIMITED,
            supervisor.snapshot().lastErrorCode
        )
        supervisor.recordProbeSuccess(identity)
        assertEquals(McpHealthState.HEALTHY, supervisor.snapshot().state)
        assertNull(supervisor.snapshot().lastErrorCode)

        elapsed += 10 * 60_000L
        supervisor.recordProbeFailure(identity, McpSelfHealthSupervisor.ERROR_PROBE_CONNECT)
        assertEquals(
            McpRecoveryAction.RESTART_ENGINE,
            supervisor.recordProbeFailure(identity, McpSelfHealthSupervisor.ERROR_PROBE_CONNECT)
        )
    }

    @Test
    fun failureBetweenRecoverySuccessesResetsSuccessStreak() {
        val supervisor = supervisor()
        supervisor.startSession()
        val identity = supervisor.beginEngineStart()
        supervisor.engineStartSucceeded(identity)
        supervisor.recordProbeFailure(identity, McpSelfHealthSupervisor.ERROR_PROBE_TIMEOUT)
        supervisor.recordProbeSuccess(identity)

        supervisor.recordProbeFailure(identity, McpSelfHealthSupervisor.ERROR_PROBE_TIMEOUT)
        supervisor.recordProbeSuccess(identity)

        assertEquals(McpHealthState.RECOVERING, supervisor.snapshot().state)
        assertEquals(McpSelfHealthSupervisor.ERROR_PROBE_TIMEOUT, supervisor.snapshot().lastErrorCode)
        supervisor.recordProbeSuccess(identity)
        assertEquals(McpHealthState.HEALTHY, supervisor.snapshot().state)
        assertNull(supervisor.snapshot().lastErrorCode)
    }

    @Test
    fun healthPayloadContainsOnlyStableNonSensitiveEngineIdentity() {
        val supervisor = supervisor()
        supervisor.startSession()
        val identity = supervisor.beginEngineStart()
        val json = JsonParser.parseString(Gson().toJson(supervisor.payload(identity))).asJsonObject

        assertEquals(setOf("processEpoch", "generation", "startedAt"), json.keySet())
        assertEquals(42L, json.get("processEpoch").asLong)
        assertEquals(1L, json.get("generation").asLong)
        assertEquals(50_000L, json.get("startedAt").asLong)
        assertFalse(json.toString().contains("token", ignoreCase = true))
        assertFalse(json.toString().contains("url", ignoreCase = true))
    }

    @Test
    fun selfProbeTargetMatchesConfiguredBindAddress() {
        assertEquals("127.0.0.1", selectMcpSelfProbeHost("0.0.0.0"))
        assertEquals("127.0.0.1", selectMcpSelfProbeHost("localhost"))
        assertEquals("127.0.0.1", selectMcpSelfProbeHost("127.0.0.1"))
        assertEquals("127.42.1.9", selectMcpSelfProbeHost("127.42.1.9"))
        assertEquals("::1", selectMcpSelfProbeHost("::"))
        assertEquals("::1", selectMcpSelfProbeHost("[::]"))
        assertEquals("::1", selectMcpSelfProbeHost("::1"))
        assertEquals("192.168.1.12", selectMcpSelfProbeHost("192.168.1.12"))
        assertEquals("fe80::1234", selectMcpSelfProbeHost("[fe80::1234]"))
    }

    @Test
    fun healthEndpointAddressGateAcceptsOnlySameDeviceAddresses() {
        val localAddresses = setOf("192.168.1.12", "fe80::1234%wlan0")

        assertTrue(isSameDeviceRemoteAddress("127.0.0.1", emptySet()))
        assertTrue(isSameDeviceRemoteAddress("127.42.1.9", emptySet()))
        assertTrue(isSameDeviceRemoteAddress("::1", emptySet()))
        assertTrue(isSameDeviceRemoteAddress("192.168.1.12", localAddresses))
        assertTrue(isSameDeviceRemoteAddress("fe80::1234%wlan0", localAddresses))
        assertFalse(isSameDeviceRemoteAddress("192.168.1.13", localAddresses))
        assertFalse(isSameDeviceRemoteAddress("fe80::5678%wlan0", localAddresses))
        assertFalse(isSameDeviceRemoteAddress("localhost", localAddresses))
        assertFalse(isSameDeviceRemoteAddress("example.com", localAddresses))
    }

    @Test
    fun staleGenerationCannotRestartReplacementEngine() {
        val config = McpConfig(enabled = true, host = "0.0.0.0", port = 27190)
        val staleIdentity = McpEngineIdentity(42L, 1L, 50_000L)
        val replacementIdentity = staleIdentity.copy(generation = 2L, startedAt = 50_100L)
        var restartCount = 0

        val restarted = runMcpEngineRestartIfCurrent(
            activeConfig = config,
            currentIdentity = replacementIdentity,
            expectedConfig = config,
            expectedIdentity = staleIdentity
        ) { restartCount += 1 }

        assertFalse(restarted)
        assertEquals(0, restartCount)

        val currentRestarted = runMcpEngineRestartIfCurrent(
            activeConfig = config,
            currentIdentity = replacementIdentity,
            expectedConfig = config,
            expectedIdentity = replacementIdentity
        ) { restartCount += 1 }

        assertTrue(currentRestarted)
        assertEquals(1, restartCount)
    }
}

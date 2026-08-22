package org.autojs.autoxjs.mcp

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL

internal sealed class McpProbeResult {
    data object Success : McpProbeResult()
    data class Failure(val errorCode: String) : McpProbeResult()
}

internal fun interface McpHealthProbe {
    suspend fun probe(config: McpConfig, expectedIdentity: McpEngineIdentity): McpProbeResult
}

internal class McpLocalHealthProbe(
    private val gson: Gson = Gson(),
    private val timeoutMillis: Int = PROBE_TIMEOUT_MILLIS
) : McpHealthProbe {
    override suspend fun probe(
        config: McpConfig,
        expectedIdentity: McpEngineIdentity
    ): McpProbeResult {
        return try {
            withTimeout(timeoutMillis.toLong()) {
                withContext(Dispatchers.IO) {
                    executeProbe(config, expectedIdentity)
                }
            }
        } catch (_: SocketTimeoutException) {
            McpProbeResult.Failure(McpSelfHealthSupervisor.ERROR_PROBE_TIMEOUT)
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            McpProbeResult.Failure(McpSelfHealthSupervisor.ERROR_PROBE_TIMEOUT)
        } catch (_: ConnectException) {
            McpProbeResult.Failure(McpSelfHealthSupervisor.ERROR_PROBE_CONNECT)
        } catch (_: IOException) {
            McpProbeResult.Failure(McpSelfHealthSupervisor.ERROR_PROBE_IO)
        } catch (_: Exception) {
            McpProbeResult.Failure(McpSelfHealthSupervisor.ERROR_PROBE_UNKNOWN)
        }
    }

    private fun executeProbe(
        config: McpConfig,
        expectedIdentity: McpEngineIdentity
    ): McpProbeResult {
        val probeHost = selectMcpSelfProbeHost(config.host)
        val urlHost = if (probeHost.contains(':')) "[$probeHost]" else probeHost
        val connection = URL("http://$urlHost:${config.port}/healthz")
            .openConnection() as HttpURLConnection
        val deadlineNanos = System.nanoTime() + timeoutMillis * 1_000_000L
        return try {
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = timeoutMillis
            connection.readTimeout = timeoutMillis
            connection.connect()

            connection.readTimeout = remainingTimeoutMillis(deadlineNanos)

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                return McpProbeResult.Failure(McpSelfHealthSupervisor.ERROR_PROBE_HTTP)
            }

            connection.readTimeout = remainingTimeoutMillis(deadlineNanos)
            val payload = connection.inputStream.bufferedReader().use {
                gson.fromJson(it, McpHealthPayload::class.java)
            } ?: return McpProbeResult.Failure(McpSelfHealthSupervisor.ERROR_PROBE_PAYLOAD)

            if (
                payload.processEpoch != expectedIdentity.processEpoch ||
                payload.generation != expectedIdentity.generation ||
                payload.startedAt != expectedIdentity.startedAt
            ) {
                McpProbeResult.Failure(McpSelfHealthSupervisor.ERROR_PROBE_MISMATCH)
            } else {
                McpProbeResult.Success
            }
        } catch (_: com.google.gson.JsonParseException) {
            McpProbeResult.Failure(McpSelfHealthSupervisor.ERROR_PROBE_PAYLOAD)
        } finally {
            connection.disconnect()
        }
    }

    private fun remainingTimeoutMillis(deadlineNanos: Long): Int {
        val remainingNanos = deadlineNanos - System.nanoTime()
        if (remainingNanos <= 0) {
            throw SocketTimeoutException("MCP self-probe deadline exceeded")
        }
        return (remainingNanos / 1_000_000L)
            .coerceAtLeast(1L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
    }

    companion object {
        const val PROBE_TIMEOUT_MILLIS = 2_000
    }
}

internal fun selectMcpSelfProbeHost(bindHost: String): String {
    val normalized = bindHost.trim().removeSurrounding("[", "]")
    return when {
        normalized == "0.0.0.0" -> "127.0.0.1"
        normalized.equals("localhost", ignoreCase = true) -> "127.0.0.1"
        normalized == "::" || normalized == "::1" -> "::1"
        else -> normalized
    }
}

internal fun isSameDeviceRemoteAddress(
    remoteAddress: String,
    localAddresses: Set<String> = localInterfaceAddresses()
): Boolean {
    val remote = parseNumericAddress(remoteAddress) ?: return false
    if (remote.isLoopbackAddress) {
        return true
    }
    return localAddresses.any { localAddress ->
        val local = parseNumericAddress(localAddress)
        local != null && remote.address.contentEquals(local.address)
    }
}

private fun parseNumericAddress(address: String): InetAddress? {
    val normalized = address
        .trim()
        .removePrefix("[")
        .removeSuffix("]")
        .substringBefore('%')
    if (normalized.isBlank() || normalized.any { !it.isDigit() && it !in ".:abcdefABCDEF" }) {
        return null
    }
    return try {
        InetAddress.getByName(normalized)
    } catch (_: Exception) {
        null
    }
}

private fun localInterfaceAddresses(): Set<String> {
    val addresses = mutableSetOf<String>()
    return try {
        val interfaces = java.net.NetworkInterface.getNetworkInterfaces() ?: return emptySet()
        while (interfaces.hasMoreElements()) {
            val interfaceAddresses = interfaces.nextElement().inetAddresses
            while (interfaceAddresses.hasMoreElements()) {
                interfaceAddresses.nextElement().hostAddress?.let(addresses::add)
            }
        }
        addresses
    } catch (_: Exception) {
        emptySet()
    }
}

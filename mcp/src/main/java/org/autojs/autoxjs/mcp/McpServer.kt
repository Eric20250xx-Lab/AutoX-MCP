package org.autojs.autoxjs.mcp

import android.content.Context
import android.content.pm.PackageInfo
import android.net.Uri
import android.util.Log
import com.google.gson.Gson
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import org.autojs.autoxjs.mcp.tool.ToolRegistry
import java.net.NetworkInterface

/**
 * Lightweight MCP server wrapper based on Ktor.
 */
class McpServer internal constructor(
    private val appContext: Context,
    private val registry: ToolRegistry,
    private val healthSupervisor: McpSelfHealthSupervisor
) {
    private val gson = Gson()
    private var engine: ApplicationEngine? = null
    private val jsonRpcHandler = McpJsonRpcHandler(
        registry,
        serverInfoProvider = { buildServerInfo() }
    )

    val isRunning: Boolean
        @Synchronized get() = engine != null

    @Synchronized
    fun start(config: McpConfig): Boolean {
        stop()
        if (!config.enabled) return false
        Log.i(TAG, "Starting MCP server on ${config.host}:${config.port}")
        var candidate: ApplicationEngine? = null
        val identity = healthSupervisor.beginEngineStart()
        try {
            val newEngine = embeddedServer(Netty, port = config.port, host = config.host) {
                install(WebSockets)
                routing {
                    post("/mcp") {
                        try {
                            Log.d(TAG, "Received POST /mcp request")
                            if (!isOriginAllowed(config, call.request.headers["Origin"])) {
                                Log.d(TAG, "Origin not allowed")
                                call.respond(HttpStatusCode.Forbidden)
                                return@post
                            }

                            if (!authorize(config, call.request.headers["X-Token"])) {
                                Log.d(TAG, "Unauthorized")
                                call.respond(HttpStatusCode.Unauthorized)
                                return@post
                            }

                            val body = call.receiveText()
                            Log.d(TAG, "Received body: $body")
                            val response = jsonRpcHandler.handleText(body)
                            Log.d(TAG, "Response status: ${response.status}, body: ${response.body}")
                            if (response.body == null) {
                                call.respond(response.status)
                            } else {
                                val jsonResponse = gson.toJson(response.body)
                                Log.d(TAG, "Sending JSON response: $jsonResponse")
                                call.respondText(
                                    jsonResponse,
                                    ContentType.Application.Json,
                                    response.status
                                )
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error handling POST /mcp", e)
                            call.respond(HttpStatusCode.InternalServerError)
                        }
                    }

                    get("/mcp") {
                        call.respond(HttpStatusCode.MethodNotAllowed)
                    }

                    get("/healthz") {
                        if (!isSameDeviceRemoteAddress(call.request.origin.remoteAddress)) {
                            call.respond(HttpStatusCode.Forbidden)
                            return@get
                        }
                        call.respondText(
                            gson.toJson(healthSupervisor.payload(identity)),
                            ContentType.Application.Json,
                            HttpStatusCode.OK
                        )
                    }

                    get("/") {
                        call.respondText("MCP Server Running", ContentType.Text.Plain)
                    }
                }
            }
            candidate = newEngine
            Log.i(TAG, "Engine created, starting...")
            newEngine.start(wait = false)
            engine = newEngine
            healthSupervisor.engineStartSucceeded(identity)
            Log.i(TAG, "Engine started successfully")
            Log.i(TAG, "MCP server started on ${config.host}:${config.port}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MCP server", e)
            try {
                candidate?.stop(0, 1000)
            } catch (stopError: Exception) {
                Log.w(TAG, "Failed to clean up MCP server after start failure", stopError)
            }
            engine = null
            healthSupervisor.engineStartFailed(identity)
            return false
        }
    }

    @Synchronized
    fun stop() {
        try {
            engine?.stop(1000, 2000)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping server", e)
        } finally {
            engine = null
        }
    }

    private fun authorize(config: McpConfig, tokenHeader: String?): Boolean {
        val expected = config.token
        return expected.isNullOrBlank() || expected == tokenHeader
    }

    private fun isOriginAllowed(config: McpConfig, originHeader: String?): Boolean {
        if (originHeader.isNullOrBlank()) {
            return true
        }
        val host = try {
            Uri.parse(originHeader).host
        } catch (_: Exception) {
            null
        } ?: return false

        val allowedHosts = if (config.allowNetwork) {
            localHosts()
        } else {
            setOf("localhost", "127.0.0.1", "::1")
        }
        return allowedHosts.contains(host)
    }

    private fun localHosts(): Set<String> {
        val hosts = mutableSetOf("localhost", "127.0.0.1", "::1")
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    val host = address.hostAddress?.substringBefore('%')
                    if (!host.isNullOrBlank()) {
                        hosts.add(host)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve local addresses", e)
        }
        return hosts
    }

    private fun buildServerInfo(): McpServerInfo {
        val name = "AutoX MCP"
        val versionName = try {
            val info: PackageInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
            info.versionName
        } catch (_: Exception) {
            null
        }
        return McpServerInfo(
            name = name,
            title = name,
            version = versionName,
            description = "AutoX embedded MCP tools server"
        )
    }

    companion object {
        private const val TAG = "McpServer"
    }
}

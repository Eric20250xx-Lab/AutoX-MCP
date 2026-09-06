package org.autojs.autoxjs.mcp

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

object McpPrefs {
    private const val KEY_START_RETRY_PENDING = "mcp_foreground_start_retry_pending"
    private const val DEFAULT_HOST = "0.0.0.0"
    private const val LOCAL_HOST = "127.0.0.1"
    private const val DEFAULT_PORT = 27190
    private val LOCAL_HOSTS = setOf(LOCAL_HOST, "localhost", "::1")

    @Suppress("DEPRECATION")
    private fun getSharedPreferences(context: Context): SharedPreferences {
        val name = context.packageName + "_preferences"
        return context.getSharedPreferences(name, Context.MODE_MULTI_PROCESS)
    }

    fun load(context: Context): McpConfig {
        val prefs = getSharedPreferences(context)
        val enabled = prefs.getBoolean(McpPrefKeys.KEY_ENABLED, false)
        val allowBase64 = prefs.getBoolean(McpPrefKeys.KEY_ALLOW_BASE64, false)
        val host = prefs.getString(McpPrefKeys.KEY_HOST, DEFAULT_HOST).orEmpty().ifBlank { DEFAULT_HOST }
        val allowNetworkPref = prefs.getBoolean(McpPrefKeys.KEY_ALLOW_NETWORK, true)
        val allowNetwork = allowNetworkPref || !LOCAL_HOSTS.contains(host)
        val port = prefs.getString(McpPrefKeys.KEY_PORT, DEFAULT_PORT.toString())
            ?.toIntOrNull()
            ?.coerceIn(1, 65535)
            ?: DEFAULT_PORT
        val token = prefs.getString(McpPrefKeys.KEY_TOKEN, null)?.trim().orEmpty().ifBlank { null }
        val finalHost = if (allowNetwork) host else LOCAL_HOST

        return McpConfig(
            enabled = enabled,
            host = finalHost,
            port = port,
            token = token,
            allowBase64 = allowBase64,
            allowNetwork = allowNetwork
        )
    }

    fun isEnabled(context: Context): Boolean = getSharedPreferences(context).getBoolean(McpPrefKeys.KEY_ENABLED, false)

    fun isStartRetryPending(context: Context): Boolean = getSharedPreferences(context).getBoolean(KEY_START_RETRY_PENDING, false)

    fun setStartRetryPending(context: Context, pending: Boolean): Boolean = getSharedPreferences(context)
        .edit().putBoolean(KEY_START_RETRY_PENDING, pending).commit()
}

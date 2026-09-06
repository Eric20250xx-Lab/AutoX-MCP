package org.autojs.autojs.mcp

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import androidx.preference.PreferenceManager
import org.autojs.autoxjs.mcp.McpPrefKeys
import org.autojs.autoxjs.mcp.McpServerService

class McpPreferenceBridge(private val context: Context) :
    SharedPreferences.OnSharedPreferenceChangeListener,
    Application.ActivityLifecycleCallbacks {
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    private val application = context.applicationContext as? Application
    private val startController = McpServiceStartController(
        startService = { McpServerService.start(context) },
        stopService = { McpServerService.stop(context) }
    )

    fun start() {
        prefs.registerOnSharedPreferenceChangeListener(this)
        application?.registerActivityLifecycleCallbacks(this)
        applyState()
    }

    fun stop() {
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        application?.unregisterActivityLifecycleCallbacks(this)
        startController.apply(enabled = false)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key == McpPrefKeys.KEY_ENABLED) {
            applyState()
        }
    }

    private fun applyState() {
        val enabled = prefs.getBoolean(McpPrefKeys.KEY_ENABLED, false)
        startController.apply(enabled)
    }

    override fun onActivityResumed(activity: Activity) {
        startController.onForeground(
            enabled = prefs.getBoolean(McpPrefKeys.KEY_ENABLED, false)
        )
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}

internal class McpServiceStartController(
    private val startService: () -> Boolean,
    private val stopService: () -> Unit
) {
    private var retryPending = false

    fun apply(enabled: Boolean) {
        if (!enabled) {
            retryPending = false
            stopService()
            return
        }
        retryPending = !startService()
    }

    fun onForeground(enabled: Boolean) {
        if (retryPending) {
            apply(enabled)
        }
    }
}

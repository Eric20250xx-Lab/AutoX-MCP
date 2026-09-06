package org.autojs.autojs.mcp

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import androidx.preference.PreferenceManager
import org.autojs.autoxjs.mcp.McpPrefKeys
import org.autojs.autoxjs.mcp.McpPrefs
import org.autojs.autoxjs.mcp.McpServerService

class McpPreferenceBridge(private val context: Context, private val applyInitialState: Boolean = true) :
    SharedPreferences.OnSharedPreferenceChangeListener,
    Application.ActivityLifecycleCallbacks {
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    private val application = context.applicationContext as? Application
    private val startController = McpServiceStartController(
        startService = { McpServerService.start(context) },
        stopService = { McpServerService.stop(context) },
        loadRetryPending = { McpPrefs.isStartRetryPending(context) },
        saveRetryPending = { McpPrefs.setStartRetryPending(context, it) }
    )

    fun start() {
        prefs.registerOnSharedPreferenceChangeListener(this)
        application?.registerActivityLifecycleCallbacks(this)
        if (applyInitialState) {
            applyState()
        }
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
        startController.apply(McpPrefs.isEnabled(context))
    }

    override fun onActivityResumed(activity: Activity) {
        startController.onForeground(
            enabled = McpPrefs.isEnabled(context)
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
    private val stopService: () -> Unit,
    private val loadRetryPending: (() -> Boolean)? = null,
    private val saveRetryPending: ((Boolean) -> Unit)? = null
) {
    private var retryPending = false

    fun apply(enabled: Boolean) {
        if (!enabled) {
            setRetryPending(false)
            stopService()
            return
        }
        setRetryPending(!startService())
    }

    fun onForeground(enabled: Boolean) {
        if (loadRetryPending?.invoke() ?: retryPending) {
            apply(enabled)
        }
    }

    private fun setRetryPending(pending: Boolean) {
        retryPending = pending
        saveRetryPending?.invoke(pending)
    }
}

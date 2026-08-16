package org.autojs.autojs.guardian

import android.app.Activity
import android.app.Application
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import androidx.preference.PreferenceManager
import com.stardust.autojs.servicecomponents.EngineController
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

internal class ScriptGuardianPreferenceBridge(private val application: Application) :
    SharedPreferences.OnSharedPreferenceChangeListener,
    Application.ActivityLifecycleCallbacks {
    private val prefs = PreferenceManager.getDefaultSharedPreferences(application)
    private val watchedKeys = ScriptGuardianPrefs.watchedKeys(application)
    private val resumedActivities = ConcurrentHashMap.newKeySet<Activity>()
    private var applyJob: Job? = null
    @Volatile
    private var lastObservedEnabled = prefs.getBoolean(ScriptGuardianPrefs.KEY_ENABLED, false)

    fun start() {
        prefs.registerOnSharedPreferenceChangeListener(this)
        application.registerActivityLifecycleCallbacks(this)
    }

    fun stop() {
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        application.unregisterActivityLifecycleCallbacks(this)
        applyJob?.cancel()
        applyJob = null
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key in watchedKeys) {
            val config = ScriptGuardianPrefs.load(application)
            ScriptGuardianExecutionGuard.updateConfig(config)
            if (resumedActivities.isNotEmpty()) {
                applyState(config)
            }
        }
    }

    override fun onActivityResumed(activity: Activity) {
        resumedActivities.add(activity)
        applyState(ScriptGuardianPrefs.load(application))
    }

    override fun onActivityPaused(activity: Activity) {
        resumedActivities.remove(activity)
    }

    override fun onActivityDestroyed(activity: Activity) {
        resumedActivities.remove(activity)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    private fun applyState(config: ScriptGuardianConfig) {
        ScriptGuardianExecutionGuard.updateConfig(config)
        val wasEnabled = lastObservedEnabled
        lastObservedEnabled = config.enabled
        applyJob?.cancel()
        applyJob = EngineController.scope.launch {
            if (!config.enabled) {
                if (wasEnabled && resumedActivities.isNotEmpty()) {
                    ScriptGuardianService.stop(application)
                }
                return@launch
            }

            if (config.resolveScriptFile().isFailure) {
                if (resumedActivities.isNotEmpty()) {
                    ScriptGuardianService.applyConfig(application, config)
                }
                return@launch
            }

            val stopped = ScriptGuardianExecutionGuard.stopExistingMainProcessExecutions(config)
            if (!stopped) {
                Log.w(TAG, "Guardian start deferred because an existing script did not stop")
                return@launch
            }
            if (resumedActivities.isNotEmpty()) {
                ScriptGuardianService.applyConfig(application, config)
            }
        }
    }

    companion object {
        private const val TAG = "ScriptGuardianPrefs"
    }
}

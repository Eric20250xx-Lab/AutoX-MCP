package org.autojs.autojs.guardian

import android.app.Activity
import android.app.Application
import android.content.SharedPreferences
import android.os.Bundle
import androidx.preference.PreferenceManager
import java.util.concurrent.ConcurrentHashMap

internal sealed interface ScriptGuardianPreferenceAction {
    data class Apply(val config: ScriptGuardianConfig) : ScriptGuardianPreferenceAction
    data object Stop : ScriptGuardianPreferenceAction
    data object None : ScriptGuardianPreferenceAction
}

internal class ScriptGuardianPreferenceState(initiallyEnabled: Boolean) {
    private var lastObservedEnabled = initiallyEnabled

    @Synchronized
    fun transition(config: ScriptGuardianConfig): ScriptGuardianPreferenceAction {
        val wasEnabled = lastObservedEnabled
        lastObservedEnabled = config.enabled
        return when {
            config.enabled -> ScriptGuardianPreferenceAction.Apply(config)
            wasEnabled -> ScriptGuardianPreferenceAction.Stop
            else -> ScriptGuardianPreferenceAction.None
        }
    }
}

internal class ScriptGuardianPreferenceBridge(private val application: Application) :
    SharedPreferences.OnSharedPreferenceChangeListener,
    Application.ActivityLifecycleCallbacks {
    private val prefs = PreferenceManager.getDefaultSharedPreferences(application)
    private val watchedKeys = ScriptGuardianPrefs.watchedKeys(application)
    private val resumedActivities = ConcurrentHashMap.newKeySet<Activity>()
    private val state = ScriptGuardianPreferenceState(
        prefs.getBoolean(ScriptGuardianPrefs.KEY_ENABLED, false)
    )

    fun start() {
        prefs.registerOnSharedPreferenceChangeListener(this)
        application.registerActivityLifecycleCallbacks(this)
    }

    fun stop() {
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        application.unregisterActivityLifecycleCallbacks(this)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key in watchedKeys) {
            val config = ScriptGuardianPrefs.load(application)
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
        when (val action = state.transition(config)) {
            is ScriptGuardianPreferenceAction.Apply ->
                ScriptGuardianService.applyConfig(application, action.config)

            ScriptGuardianPreferenceAction.Stop -> ScriptGuardianService.stop(application)
            ScriptGuardianPreferenceAction.None -> Unit
        }
    }
}

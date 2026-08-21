package org.autojs.autojs.ui.settings

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateUtils
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreference
import com.stardust.pio.PFiles
import de.psdev.licensesdialog.LicensesDialog
import org.autojs.autojs.external.open.RunIntentActivity
import org.autojs.autojs.guardian.ScriptGuardianDiagnosticSnapshot
import org.autojs.autojs.guardian.ScriptGuardianDiagnostics
import org.autojs.autojs.guardian.ScriptGuardianPrewarmPrefs
import org.autojs.autojs.guardian.ScriptGuardianPrewarmScheduler
import org.autojs.autojs.guardian.ScriptGuardianPrewarmSnapshot
import org.autojs.autojs.guardian.parseScriptGuardianPrewarmTimes
import org.autojs.autojs.ui.widget.CommonMarkdownView
import org.autojs.autoxjs.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class PreferenceFragment : PreferenceFragmentCompat() {
    private val ACTION_MAP = mutableMapOf<String, (activity: Activity) -> Unit>()
    private val statusRefreshHandler = Handler(Looper.getMainLooper())
    private val statusRefresh = object : Runnable {
        override fun run() {
            if (!isResumed) return
            updateScriptGuardianBackgroundStatus()
            updateScriptGuardianPrewarmStatus()
            statusRefreshHandler.postDelayed(this, STATUS_REFRESH_INTERVAL_MILLIS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ACTION_MAP.apply {
            //.put(getString(R.string.text_theme_color), () -> selectThemeColor(getActivity()))
            // .put(getString(R.string.text_check_for_updates), () -> new UpdateCheckDialog(getActivity()).show())
            // .put(getString(R.string.text_issue_report), () -> startActivity(new Intent(getActivity(), IssueReporterActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)))
            put(getString(R.string.text_about_me_and_repo)) {
                it.startActivity(
                    Intent(it, AboutActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            put(getString(R.string.text_licenses)) { showLicenseDialog(it) }
            put(getString(R.string.text_licenses_other)) { showLicenseDialog2(it) }
        }

    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.preferences)
        configureScriptGuardianPrewarmTimes()
    }

    override fun onResume() {
        super.onResume()
        ScriptGuardianPrewarmScheduler.reconcile(requireContext(), "settings_resumed")
        statusRefreshHandler.removeCallbacks(statusRefresh)
        statusRefresh.run()
    }

    override fun onPause() {
        statusRefreshHandler.removeCallbacks(statusRefresh)
        super.onPause()
    }

    override fun onDisplayPreferenceDialog(preference: Preference) {
        if (preference is ScriptDirPathPreference) {
            ScriptDirPathPreferenceFragmentCompat.newInstance(preference.getKey())?.let {
                it.setTargetFragment(this, 0)
                it.show(parentFragmentManager, DIALOG_FRAGMENT_TAG)
                return
            }
        }
        if (preference is EditTextPreference) {
            M3EditTextPreferenceDialogFragment(preference).show(
                parentFragmentManager, DIALOG_FRAGMENT_TAG
            )
            return
        }

        super.onDisplayPreferenceDialog(preference)
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        val action = ACTION_MAP[preference.title.toString()]
        val activity = requireActivity()
        if (preference.key == getString(R.string.key_script_guardian_background_status)) {
            openScriptGuardianBackgroundSettings(activity)
            return true
        }
        if (
            preference.key == getString(R.string.key_script_guardian_prewarm_enabled) &&
            (preference as SwitchPreference).isChecked &&
            !ScriptGuardianPrewarmScheduler.canScheduleExactAlarms(activity)
        ) {
            openScriptGuardianExactAlarmSettings(activity)
            return true
        }
        if (preference.title == getString(R.string.text_intent_run_script)) {
            val state = if ((preference as SwitchPreference).isChecked) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            activity.packageManager.setComponentEnabledSetting(
                ComponentName(activity, RunIntentActivity::class.java),
                state,
                PackageManager.DONT_KILL_APP
            );
            return true
        }
        return if (action != null) {
            action(activity)
            true
        } else {
            super.onPreferenceTreeClick(preference)
        }
    }

    private fun updateScriptGuardianBackgroundStatus() {
        val preference = findPreference<Preference>(
            getString(R.string.key_script_guardian_background_status)
        ) ?: return
        val context = requireContext()
        val powerManager = context.getSystemService(PowerManager::class.java)
        val batteryText = buildString {
            append(
                getString(
                    if (powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
                        R.string.script_guardian_battery_ready
                    } else {
                        R.string.script_guardian_battery_action_required
                    }
                )
            )
            if (powerManager.isDeviceIdleMode) {
                append(getString(R.string.script_guardian_device_idle))
            }
        }
        val snapshot = ScriptGuardianDiagnostics.snapshot(context)
        val stateText = getString(
            when (snapshot.state) {
                ScriptGuardianDiagnosticSnapshot.STATE_STARTING ->
                    R.string.script_guardian_state_starting

                ScriptGuardianDiagnosticSnapshot.STATE_RUNNING ->
                    R.string.script_guardian_state_running

                ScriptGuardianDiagnosticSnapshot.STATE_IDLE ->
                    R.string.script_guardian_state_idle

                ScriptGuardianDiagnosticSnapshot.STATE_BUSY ->
                    R.string.script_guardian_state_busy

                ScriptGuardianDiagnosticSnapshot.STATE_STOPPING ->
                    R.string.script_guardian_state_stopping

                ScriptGuardianDiagnosticSnapshot.STATE_RETRYING ->
                    R.string.script_guardian_state_retrying

                ScriptGuardianDiagnosticSnapshot.STATE_BUSY_WARNING ->
                    R.string.script_guardian_state_busy_warning

                else -> R.string.script_guardian_state_stopped
            }
        )
        val wakeLockText = getString(
            if (snapshot.hasFreshWakeLock()) {
                R.string.script_guardian_wake_lock_held
            } else {
                R.string.script_guardian_wake_lock_released
            }
        )
        val heartbeatText = snapshot.lastHeartbeatAt.takeIf { it > 0L }?.let { timestamp ->
            getString(
                R.string.script_guardian_last_heartbeat,
                DateUtils.getRelativeTimeSpanString(
                    timestamp,
                    System.currentTimeMillis(),
                    DateUtils.SECOND_IN_MILLIS
                )
            )
        }.orEmpty()
        preference.summary = getString(
            R.string.summary_script_guardian_background_status,
            batteryText,
            stateText,
            wakeLockText,
            heartbeatText
        )
    }

    private fun configureScriptGuardianPrewarmTimes() {
        val preference = findPreference<EditTextPreference>(
            getString(R.string.key_script_guardian_prewarm_times)
        ) ?: return
        preference.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, value ->
            val valid = parseScriptGuardianPrewarmTimes(value?.toString().orEmpty())
                .getOrNull()
                ?.isNotEmpty() == true
            if (!valid) {
                Toast.makeText(
                    requireContext(),
                    R.string.script_guardian_prewarm_invalid_input,
                    Toast.LENGTH_LONG
                ).show()
            }
            valid
        }
    }

    private fun updateScriptGuardianPrewarmStatus() {
        val preference = findPreference<SwitchPreference>(
            getString(R.string.key_script_guardian_prewarm_enabled)
        ) ?: return
        val timesPreference = findPreference<EditTextPreference>(
            getString(R.string.key_script_guardian_prewarm_times)
        )
        val rawTimes = timesPreference?.text ?: ScriptGuardianPrewarmPrefs.DEFAULT_TIMES
        val times = parseScriptGuardianPrewarmTimes(rawTimes).getOrNull()
            ?.joinToString(",") { "%02d:%02d".format(it.hour, it.minute) }
            .orEmpty()
        val snapshot = ScriptGuardianPrewarmScheduler.snapshot(requireContext())
        preference.summary = when {
            !preference.isChecked -> getString(R.string.summary_script_guardian_prewarm_disabled)
            snapshot.state == ScriptGuardianPrewarmSnapshot.STATE_SCHEDULED -> getString(
                R.string.summary_script_guardian_prewarm_scheduled,
                times,
                formatScriptGuardianPrewarmTime(snapshot.nextTriggerAtMillis)
            )
            snapshot.state ==
                ScriptGuardianPrewarmSnapshot.STATE_EXACT_ALARM_PERMISSION_REQUIRED -> getString(
                    R.string.summary_script_guardian_prewarm_permission_required,
                    times
                )
            snapshot.state == ScriptGuardianPrewarmSnapshot.STATE_INVALID_TIMES ||
                snapshot.state == ScriptGuardianPrewarmSnapshot.STATE_EMPTY_TIMES -> getString(
                    R.string.summary_script_guardian_prewarm_invalid
                )
            snapshot.state == ScriptGuardianPrewarmSnapshot.STATE_ERROR -> getString(
                R.string.summary_script_guardian_prewarm_error,
                snapshot.state
            )
            else -> getString(R.string.summary_script_guardian_prewarm_disabled)
        }
    }

    private fun formatScriptGuardianPrewarmTime(timestamp: Long): String =
        Instant.ofEpochMilli(timestamp)
            .atZone(SCRIPT_GUARDIAN_PREWARM_ZONE)
            .format(SCRIPT_GUARDIAN_PREWARM_FORMATTER)

    @SuppressLint("BatteryLife")
    private fun openScriptGuardianBackgroundSettings(activity: Activity) {
        val powerManager = activity.getSystemService(PowerManager::class.java)
        val packageUri = Uri.parse("package:${activity.packageName}")
        val primary = if (powerManager.isIgnoringBatteryOptimizations(activity.packageName)) {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
        } else {
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri)
        }
        try {
            activity.startActivity(primary)
        } catch (_: ActivityNotFoundException) {
            try {
                activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: ActivityNotFoundException) {
                activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri))
            }
        }
    }

    private fun openScriptGuardianExactAlarmSettings(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val packageUri = Uri.parse("package:${activity.packageName}")
        try {
            activity.startActivity(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri)
            )
        } catch (_: ActivityNotFoundException) {
            activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri))
        }
    }

    companion object {
        const val DIALOG_FRAGMENT_TAG = "org.autojs.autojs.ui.settings.PreferenceFragment.DIALOG";
        private const val STATUS_REFRESH_INTERVAL_MILLIS = 5_000L
        private val SCRIPT_GUARDIAN_PREWARM_ZONE = ZoneId.of("Asia/Shanghai")
        private val SCRIPT_GUARDIAN_PREWARM_FORMATTER =
            DateTimeFormatter.ofPattern("MM-dd HH:mm")

        private fun showLicenseDialog(context: Context) {
            LicensesDialog.Builder(context)
                .setNotices(R.raw.licenses)
                .setIncludeOwnLicense(true)
                .build()
                .show()
        }

        private fun showLicenseDialog2(context: Context) {
            CommonMarkdownView.DialogBuilder(context)
                .padding(36, 0, 36, 0)
                .markdown(PFiles.read(context.resources.openRawResource(R.raw.licenses_other)))
                .title(R.string.text_licenses_other)
                .positiveText(R.string.ok)
                .canceledOnTouchOutside(false)
                .show()
        }
    }
}

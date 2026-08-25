package org.autojs.autojs

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build

internal enum class AppProcessRole {
    MAIN,
    SCRIPT,
    WATCHDOG,
    ATTENDANCE_PROBE,
    OTHER;

    companion object {
        private const val WATCHDOG_PROCESS_SUFFIX = ":guardian"
        private const val ATTENDANCE_PROBE_PROCESS_SUFFIX = ":attendance_probe"

        fun resolve(
            packageName: String,
            processName: String?,
            scriptProcessSuffix: String
        ): AppProcessRole = when (processName) {
            packageName -> MAIN
            packageName + scriptProcessSuffix -> SCRIPT
            packageName + WATCHDOG_PROCESS_SUFFIX -> WATCHDOG
            packageName + ATTENDANCE_PROBE_PROCESS_SUFFIX -> ATTENDANCE_PROBE
            else -> OTHER
        }

        fun currentProcessName(context: Context): String? {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Application.getProcessName()?.let { return it }
            }
            val pid = android.os.Process.myPid()
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            return manager?.runningAppProcesses
                ?.firstOrNull { it.pid == pid }
                ?.processName
        }
    }
}

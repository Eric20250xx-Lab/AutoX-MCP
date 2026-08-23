package org.autojs.autojs

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build

internal enum class AppProcessRole {
    MAIN,
    SCRIPT,
    WATCHDOG,
    OTHER;

    companion object {
        private const val WATCHDOG_PROCESS_SUFFIX = ":guardian"

        fun resolve(
            packageName: String,
            processName: String?,
            scriptProcessSuffix: String
        ): AppProcessRole = when (processName) {
            packageName -> MAIN
            packageName + scriptProcessSuffix -> SCRIPT
            packageName + WATCHDOG_PROCESS_SUFFIX -> WATCHDOG
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

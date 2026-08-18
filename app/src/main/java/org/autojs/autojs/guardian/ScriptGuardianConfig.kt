package org.autojs.autojs.guardian

import android.content.Context
import androidx.preference.PreferenceManager
import org.autojs.autojs.Pref
import org.autojs.autoxjs.R
import java.io.File

internal data class ScriptGuardianConfig(
    val enabled: Boolean,
    val relativePath: String,
    val scriptRoot: String
) {
    fun resolveScriptFile(): Result<File> = runCatching {
        require(enabled) { "script guardian is disabled" }
        val normalized = relativePath.trim().replace('\\', '/')
        require(normalized.isNotEmpty()) { "script path is empty" }
        require(!File(normalized).isAbsolute) { "script path must be relative" }
        val segments = normalized.split('/')
        require(segments.none { it.isEmpty() || it == "." || it == ".." }) {
            "script path contains an invalid segment"
        }

        val root = File(scriptRoot).canonicalFile
        val candidate = File(root, normalized).canonicalFile
        require(candidate.path.startsWith(root.path + File.separator)) {
            "script path is outside the AutoX script directory"
        }
        candidate
    }
}

internal object ScriptGuardianPrefs {
    const val KEY_ENABLED = "key_script_guardian_enabled"
    const val KEY_PATH = "key_script_guardian_path"

    fun load(context: Context): ScriptGuardianConfig {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return ScriptGuardianConfig(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            relativePath = prefs.getString(KEY_PATH, "").orEmpty(),
            scriptRoot = Pref.getScriptDirPath()
        )
    }

    fun watchedKeys(context: Context): Set<String> = setOf(
        KEY_ENABLED,
        KEY_PATH,
        context.getString(R.string.key_script_dir_path)
    )
}

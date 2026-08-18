package com.stardust.autojs.script

import com.aiselp.autox.engine.NodeScriptSource
import java.io.File

/** Returns the backing script file when this source represents a file on disk. */
fun ScriptSource.sourceFileOrNull(): File? = when (this) {
    is JavaScriptFileSource -> file
    is NodeScriptSource -> file
    is AutoFileSource -> file
    else -> null
}

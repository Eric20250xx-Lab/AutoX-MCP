package com.aiselp.debug

import android.app.Application

class ObjectWatcher : com.stardust.autojs.util.ObjectWatcher {
    override fun watch(watchedObject: Any, description: String) {}

    override fun init(app: Application) {}
}

package com.stardust.automator

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.ViewConfiguration
import androidx.annotation.RequiresApi
import com.stardust.concurrent.VolatileBox
import com.stardust.concurrent.VolatileDispose
import com.stardust.util.ScreenMetrics
import java.util.concurrent.Executors

/**
 * Created by Stardust on 2017/5/16.
 */

class GlobalActionAutomator(private val mHandler: Handler?, private val serviceProvider: () -> AccessibilityService) {

    private val service: AccessibilityService
        get() = serviceProvider()

    private var mScreenMetrics: ScreenMetrics? = null

    fun setScreenMetrics(screenMetrics: ScreenMetrics?) {
        mScreenMetrics = screenMetrics
    }

    fun back(): Boolean {
        return performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    }

    fun home(): Boolean {
        return performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
    }

    fun powerDialog(): Boolean {
        return performGlobalAction(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG)
    }

    private fun performGlobalAction(globalAction: Int): Boolean {
        return service.performGlobalAction(globalAction)
    }

    fun notifications(): Boolean {
        return performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
    }

    fun quickSettings(): Boolean {
        return performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
    }

    fun recents(): Boolean {
        return performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
    }

    /**
     * Action to take a screenshot
     */
    @RequiresApi(Build.VERSION_CODES.P)
    fun takeScreenshot(): Boolean {
        return performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
    }

    /**
     * Action to lock the screen
     */
    @RequiresApi(Build.VERSION_CODES.P)
    fun lockScreen(): Boolean {
        return performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
    }

    /**
     * Action to dismiss the notification shade
     */
    @RequiresApi(Build.VERSION_CODES.S)
    fun dismissNotificationShade(): Boolean {
        return performGlobalAction(AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
    }

    /**
     * Action to send the KEYCODE_HEADSETHOOK KeyEvent, which is used to answer/hang up
     * calls and play/stop media
     */
    @RequiresApi(Build.VERSION_CODES.S)
    fun keyCodeHeadsetHook(): Boolean {
        return performGlobalAction(AccessibilityService.GLOBAL_ACTION_KEYCODE_HEADSETHOOK)
    }

    /**
     * Action to trigger the Accessibility Shortcut. This shortcut has a hardware trigger
     * and can be activated by holding down the two volume keys.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    fun accessibilityShortcut(): Boolean {
        return performGlobalAction(AccessibilityService.GLOBAL_ACTION_ACCESSIBILITY_SHORTCUT)
    }

    /**
     * Action to bring up the Accessibility Button’s chooser menu
     */
    @RequiresApi(Build.VERSION_CODES.S)
    fun accessibilityButtonChooser(): Boolean {
        return performGlobalAction(AccessibilityService.GLOBAL_ACTION_ACCESSIBILITY_BUTTON_CHOOSER)
    }

    /**
     * Action to trigger the Accessibility Button
     */
    @RequiresApi(Build.VERSION_CODES.S)
    fun accessibilityButton(): Boolean {
        return performGlobalAction(AccessibilityService.GLOBAL_ACTION_ACCESSIBILITY_BUTTON)
    }

    /**
     * Action to show Launcher’s all apps.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    fun accessibilityAllApps(): Boolean {
        return performGlobalAction(AccessibilityService.GLOBAL_ACTION_ACCESSIBILITY_ALL_APPS)
    }

    /**
     * Action to trigger dpad up keyevent.
     */
    fun dpadUp(): Boolean {
//        return performGlobalAction(AccessibilityService.GLOBAL_ACTION_DPAD_UP)
        // TODO: 待适配Api Tiramisu
        return false
    }

    /**
     * Action to trigger dpad down keyevent.
     */
    fun dpadDown(): Boolean {
//        return performGlobalAction(AccessibilityService.GLOBAL_ACTION_DPAD_DOWN)
        // TODO: 待适配Api Tiramisu
        return false
    }

    /**
     * Action to trigger dpad right keyevent.
     */
    fun dpadRight(): Boolean {
//        return performGlobalAction(AccessibilityService.GLOBAL_ACTION_DPAD_RIGHT)
        // TODO: 待适配Api Tiramisu
        return false
    }

    /**
     * Action to trigger dpad left keyevent.
     */
    fun dpadLeft(): Boolean {
//        return performGlobalAction(AccessibilityService.GLOBAL_ACTION_DPAD_LEFT)
        // TODO: 待适配Api Tiramisu
        return false
    }

    /**
     * Action to trigger dpad center keyevent.
     */
    fun dpadCenter(): Boolean {
//        return performGlobalAction(AccessibilityService.GLOBAL_ACTION_DPAD_CENTER)
        // TODO: 待适配Api Tiramisu
        return false
    }

    fun splitScreen(): Boolean {
        return performGlobalAction(AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
    }

    fun gesture(start: Long, duration: Long, vararg points: IntArray): Boolean {
        val path = pointsToPath(points)
        return gestures(GestureDescription.StrokeDescription(path, start, duration))
    }

    private fun pointsToPath(points: Array<out IntArray>): Path {
        val path = Path()
        path.moveTo(scaleX(points[0][0]).toFloat(), scaleY(points[0][1]).toFloat())
        for (i in 1 until points.size) {
            val point = points[i]
            path.lineTo(scaleX(point[0]).toFloat(), scaleY(point[1]).toFloat())
        }
        return path
    }

    fun gestureAsync(start: Long, duration: Long, vararg points: IntArray) {
        val path = pointsToPath(points)
        gesturesAsync(GestureDescription.StrokeDescription(path, start, duration))
    }

    fun gestures(vararg strokes: GestureDescription.StrokeDescription): Boolean {
        val builder = GestureDescription.Builder()
        for (stroke in strokes) {
            builder.addStroke(stroke)
        }
        val handler = mHandler
        return if (handler == null) {
            gesturesWithoutHandler(builder.build())
        } else {
            gesturesWithHandler(handler, builder.build())
        }
    }

    private fun gesturesWithHandler(handler: Handler, description: GestureDescription): Boolean {
        val lease = acquireGestureLease() ?: return false
        scheduleLeaseTimeout(lease, description)
        val result = VolatileDispose<Boolean>()
        val accepted = runCatching {
            service.dispatchGesture(
                description,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription) {
                        lease.close()
                        result.setAndNotify(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription) {
                        lease.close()
                        result.setAndNotify(false)
                    }
                },
                handler
            )
        }.getOrElse {
            lease.close()
            throw it
        }
        if (!accepted) {
            lease.close()
            return false
        }
        return result.blockedGet()
    }

    private fun gesturesWithoutHandler(description: GestureDescription): Boolean {
        val lease = acquireGestureLease() ?: return false
        scheduleLeaseTimeout(lease, description)
        prepareLooperIfNeeded()
        val result = VolatileBox(false)
        val handler = Looper.myLooper()?.let { Handler(it) }
        val accepted = runCatching {
            service.dispatchGesture(
                description,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription) {
                        lease.close()
                        result.set(true)
                        quitLoop()
                    }

                    override fun onCancelled(gestureDescription: GestureDescription) {
                        lease.close()
                        result.set(false)
                        quitLoop()
                    }
                },
                handler
            )
        }.getOrElse {
            lease.close()
            throw it
        }
        if (!accepted) {
            lease.close()
            return false
        }
        Looper.loop()
        return result.get()
    }

    fun gesturesAsync(vararg strokes: GestureDescription.StrokeDescription) {
        val builder = GestureDescription.Builder()
        for (stroke in strokes) {
            builder.addStroke(stroke)
        }
        val description = builder.build()
        GESTURE_EXECUTOR.execute {
            val lease = AccessibilityGestureCoordinator.acquire() ?: return@execute
            val posted = MAIN_HANDLER.post {
                scheduleLeaseTimeout(lease, description)
                val accepted = runCatching {
                    service.dispatchGesture(
                        description,
                        object : AccessibilityService.GestureResultCallback() {
                            override fun onCompleted(gestureDescription: GestureDescription) {
                                lease.close()
                            }

                            override fun onCancelled(gestureDescription: GestureDescription) {
                                lease.close()
                            }
                        },
                        MAIN_HANDLER
                    )
                }.getOrElse {
                    lease.close()
                    false
                }
                if (!accepted) lease.close()
            }
            if (!posted) lease.close()
        }
    }

    private fun acquireGestureLease(): AccessibilityGestureCoordinator.Lease? =
        if (Looper.myLooper() == Looper.getMainLooper()) {
            AccessibilityGestureCoordinator.tryAcquire()
        } else {
            AccessibilityGestureCoordinator.acquire()
        }

    private fun scheduleLeaseTimeout(
        lease: AccessibilityGestureCoordinator.Lease,
        description: GestureDescription
    ) {
        var latestStrokeEndMillis = 0L
        for (index in 0 until description.strokeCount) {
            val stroke = description.getStroke(index)
            latestStrokeEndMillis = maxOf(
                latestStrokeEndMillis,
                stroke.startTime + stroke.duration
            )
        }
        MAIN_HANDLER.postDelayed(
            lease::close,
            latestStrokeEndMillis + GESTURE_LEASE_TIMEOUT_MARGIN_MILLIS
        )
    }

    private fun quitLoop() {
        val looper = Looper.myLooper()
        looper?.quit()
    }

    private fun prepareLooperIfNeeded() {
        if (Looper.myLooper() == null) {
            Looper.prepare()
        }
    }

    fun click(x: Int, y: Int): Boolean {
        return press(x, y, ViewConfiguration.getTapTimeout() + 50)
    }

    fun press(x: Int, y: Int, delay: Int): Boolean {
        return gesture(0, delay.toLong(), intArrayOf(x, y))
    }

    fun longClick(x: Int, y: Int): Boolean {
        return gesture(0, (ViewConfiguration.getLongPressTimeout() + 200).toLong(), intArrayOf(x, y))
    }

    private fun scaleX(x: Int): Int {
        return ScreenMetrics.scaleX(x)
    }

    private fun scaleY(y: Int): Int {
        return ScreenMetrics.scaleX(y)
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, delay: Long): Boolean {
        return gesture(0, delay, intArrayOf(x1, y1), intArrayOf(x2, y2))
    }

    companion object {
        private val MAIN_HANDLER = Handler(Looper.getMainLooper())
        private val GESTURE_EXECUTOR = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "AutoX-GestureQueue").apply { isDaemon = true }
        }
        private const val GESTURE_LEASE_TIMEOUT_MARGIN_MILLIS = 2_000L
    }

}

package dev.abhay.assistant

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.lang.ref.WeakReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether one of the app's screens is in front (resumed), for waiting until another app has handed
 * back; and which screen, so intents can be started from it, in the assistant's own task.
 */
class ForegroundTracker(app: Application) {
    private val _inFront = MutableStateFlow(false)
    val inFront: StateFlow<Boolean> = _inFront.asStateFlow()

    private var resumed: WeakReference<Activity>? = null
    /** The app's screen in front, if one is. */
    val activity: Activity? get() = resumed?.get()?.takeIf { _inFront.value }

    init {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                resumed = WeakReference(activity)
                _inFront.value = true
            }
            override fun onActivityPaused(activity: Activity) { _inFront.value = false }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}

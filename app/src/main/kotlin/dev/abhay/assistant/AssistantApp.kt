package dev.abhay.assistant

import android.app.Application
import dev.abhay.assistant.engine.AssistantEngine
import dev.abhay.assistant.tools.ContactBook
import dev.abhay.assistant.tools.InstalledApps
import dev.abhay.assistant.tools.ToolSettings

/** One model, one tool configuration, one app list and one contact book per process, shared by the screens. */
class AssistantApp : Application() {
    val engine by lazy { AssistantEngine(this) }
    val toolSettings by lazy { ToolSettings(this) }
    val apps by lazy { InstalledApps.from(this) }
    val contacts by lazy { ContactBook.from(this) }
    lateinit var foreground: ForegroundTracker
        private set

    override fun onCreate() {
        super.onCreate()
        // Before any activity starts, so the first resume is seen.
        foreground = ForegroundTracker(this)
    }
}

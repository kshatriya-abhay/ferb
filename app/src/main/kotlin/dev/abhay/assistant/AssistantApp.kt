package dev.abhay.assistant

import android.app.Application
import dev.abhay.assistant.engine.AssistantEngine
import dev.abhay.assistant.tools.CalendarBook
import dev.abhay.assistant.tools.ContactBook
import dev.abhay.assistant.tools.InstalledApps
import dev.abhay.assistant.tools.ToolSettings
import dev.abhay.assistant.link.FerbLink
import dev.abhay.assistant.link.LinkSettings
import dev.abhay.assistant.notes.SafNotes
import dev.abhay.assistant.onboarding.Onboarding

/** One model, one tool configuration, one app list, one contact book and one calendar per process, shared by the screens. */
class AssistantApp : Application() {
    val engine by lazy { AssistantEngine(this) }
    val toolSettings by lazy { ToolSettings(this) }
    val apps by lazy { InstalledApps.from(this) }
    val contacts by lazy { ContactBook.from(this) }
    val calendar by lazy { CalendarBook.from(this) }
    val link by lazy { FerbLink(this) }
    val linkSettings by lazy { LinkSettings(this) }
    val onboarding by lazy { Onboarding.from(this) }
    val notes by lazy { SafNotes(this) }
    lateinit var foreground: ForegroundTracker
        private set

    override fun onCreate() {
        super.onCreate()
        // Before any activity starts, so the first resume is seen.
        foreground = ForegroundTracker(this)
    }
}

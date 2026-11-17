package dev.abhay.assistant.onboarding

import dev.abhay.assistant.tools.AddEvent
import dev.abhay.assistant.tools.AddReminder
import dev.abhay.assistant.tools.AddToList
import dev.abhay.assistant.tools.CheckOff
import dev.abhay.assistant.tools.MakeCall
import dev.abhay.assistant.tools.SendText
import dev.abhay.assistant.tools.ShowAgenda
import dev.abhay.assistant.tools.ShowList
import dev.abhay.assistant.tools.Tool
import dev.abhay.assistant.tools.ToolConfig

/**
 * The tools that need setup before they can run, grouped as setup's tools page ([Page.Tools])
 * shows them: one row each, set up in place. The others are on from the start, so they aren't
 * listed. Reminders need only notifications: exact alarms come with `USE_EXACT_ALARM`, granted
 * at install.
 */
enum class SetupGroup(val title: String, val tools: List<Tool>, val needsFolder: Boolean = false) {
    CallsAndMessages("Calls & Messages", listOf(MakeCall, SendText)),
    AgendaAndCalendar("Agenda & Calendar", listOf(ShowAgenda, AddEvent)),
    Reminders("Reminders", listOf(AddReminder)),
    Lists("Lists", listOf(AddToList, ShowList, CheckOff), needsFolder = true);

    /** The Android permissions its tools ask for, asked together. */
    val permissions: List<String> get() = tools.flatMap { it.permissions }.distinct()

    /**
     * The row as setup shows it: the switch means "Ferb can do this now" (review P2-148,
     * ferb-manager's call). On: a tool of the group is on, with every permission granted (and the notes
     * folder picked, for Lists). Missing: on, but a permission or the folder isn't there (refused,
     * or taken back since): requests still reach the tools, which say what's missing and offer
     * Allow. Off: none of its tools is on.
     */
    fun state(configs: Map<String, ToolConfig>, granted: (String) -> Boolean, folder: Boolean): RowState = when {
        tools.none { configs[it.name]?.enabled == true } -> RowState.Off
        permissions.all(granted) && (!needsFolder || folder) -> RowState.On
        else -> RowState.Missing
    }
}

enum class RowState { On, Missing, Off }

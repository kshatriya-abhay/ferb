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
     * Its tools that can run now: the ones whose permissions are all [granted], and only with a
     * notes folder picked if it [needsFolder]. E.g. contacts allowed but not calls: Messages only.
     */
    fun usable(granted: (String) -> Boolean, folder: Boolean): List<Tool> =
        if (needsFolder && !folder) emptyList() else tools.filter { it.permissions.all(granted) }

    /** The row's switch: on once something is set up and all that can run is on. */
    fun on(configs: Map<String, ToolConfig>, granted: (String) -> Boolean, folder: Boolean): Boolean =
        usable(granted, folder).let { usable -> usable.isNotEmpty() && usable.all { configs[it.name]?.enabled == true } }
}

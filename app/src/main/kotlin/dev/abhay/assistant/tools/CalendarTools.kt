package dev.abhay.assistant.tools

import android.Manifest
import android.content.Context
import dev.abhay.assistant.R
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What the calendar tools say without the permission. */
private const val NO_CALENDAR = "Ferb needs your calendar to show your day. It stays on this phone."

/**
 * The request itself, added to the arguments by [Tool.fromRequest]: the calendar tools read the day
 * and time from it ([When]), as Needle 3 doesn't know today's date.
 */
private const val REQUEST = "_request"

/** "Fri 9 Oct", with the year when it isn't [today]'s. */
internal fun dayName(day: LocalDate, today: LocalDate): String =
    day.format(DateTimeFormatter.ofPattern(if (day.year == today.year) "EEE d MMM" else "EEE d MMM yyyy", Locale.UK))

/** "17:00", as the alarms show times. */
internal fun clockTime(time: LocalTime): String = time.format(DateTimeFormatter.ofPattern("HH:mm", Locale.US))

object ShowAgenda : Tool(
    name = "show_agenda",
    title = "Agenda",
    icon = R.drawable.ic_agenda,
    description = "Show a day's events from your calendar, here in the chat.",
    onByDefault = false,
    // On tool-check (2026-10-10), right calls scored 0.22–0.94; the one wrong call that gets past
    // the keywords and the day check, 0.15. A wrong one only shows a list.
    defaultRunFrom = 0.3,
    jevMayRun = true,
    examples = listOf("What's on tomorrow?", "Am I free on Friday?"),
) {
    override val permissions = listOf(Manifest.permission.READ_CALENDAR)

    override val keywords = listOf("calendar", "agenda", "schedule", "what's on", "whats on", "am i free", "am i busy", "plans", "meetings", "appointments", "events")

    override val schema = """
        {"name": "show_agenda",
         "description": "Show the events in the calendar on a day, such as what's on tomorrow",
         "parameters": {"type": "object", "properties": {
           "day": {"type": "string", "description": "The day, such as today, tomorrow or Friday"}},
           "required": ["day"]}}
    """.trimIndent()

    override fun fromRequest(arguments: Map<String, Any?>, request: String) = arguments + (REQUEST to request)

    /**
     * The day Needle 3 gives must be one: "what's the agenda of the G20 summit" came back as the
     * day "G20 summit", and "what is a calendar year" as "year" (0.52 and 0.70 on tool-check).
     */
    override fun grounded(arguments: Map<String, Any?>, request: String) =
        arguments.text("day")?.let { When.day(it, LocalDate.now()) != null } ?: true

    /** The day [request] names, else the one Needle 3 gave, else today. */
    fun dayFor(arguments: Map<String, Any?>, today: LocalDate): LocalDate =
        arguments.text(REQUEST)?.let { When.day(it, today) } ?: arguments.text("day")?.let { When.day(it, today) } ?: today

    override fun prepare(arguments: Map<String, Any?>, env: PrepareEnv): Prepared {
        val today = env.now.toLocalDate()
        val day = dayFor(arguments, today)
        val zone = env.now.zone
        // Without the calendar: the calendar app at that day.
        if (env.calendar.calendars() == null) {
            return Prepared.Ready("Open the calendar on ${dayName(day, today)}", detail = NO_CALENDAR, confirm = "Open calendar",
                needs = listOf(Manifest.permission.READ_CALENDAR)) { context -> Tools.start(context, CalendarBook.viewDay(day, zone), "Opened the calendar") }
        }
        return Prepared.Ready("Show ${dayName(day, today)}", opensApp = false) { _ ->
            val events = env.calendar.day(day) ?: return@Ready Outcome.Failed(NO_CALENDAR, needs = listOf(Manifest.permission.READ_CALENDAR))
            agenda(day, today, events, zone)
        }
    }

    /** The day's events as a list, with a button to open the calendar at that day (which keeps the list). */
    fun agenda(day: LocalDate, today: LocalDate, events: List<CalendarEvent>, zone: ZoneId): Outcome.Done {
        val near = if (day == today) "Today, " else if (day == today.plusDays(1)) "Tomorrow, " else ""
        val name = near + dayName(day, today)
        val message = when (events.size) {
            // "Nothing on Sat 10 Oct", but "Today, Mon 12 Oct: nothing on" (not "Nothing on Today").
            0 -> if (near.isEmpty()) "Nothing on $name" else "$name: nothing on"
            1 -> "$name: 1 event"
            else -> "$name: ${events.size} events"
        }
        val lines = events.map { e ->
            Line(lead(e, day, zone), e.title, e.location, open = { CalendarBook.viewEvent(e.id, e.begin, e.end) })
        }
        fun shown(): Outcome.Done = Outcome.Done(message, lines = lines, actions = listOf(FollowUp("Open calendar") { env ->
            val opened = Tools.start(env.context, CalendarBook.viewDay(day, zone), "")
            if (opened is Outcome.Failed) opened else shown()
        }))
        return shown()
    }

    /** "All day", "10:00–10:30", or for an event that runs past midnight, "from 22:00" or "until 02:00". */
    private fun lead(e: CalendarEvent, day: LocalDate, zone: ZoneId): String {
        if (e.allDay) return "All day"
        val begin = Instant.ofEpochMilli(e.begin).atZone(zone).toLocalDateTime()
        val end = Instant.ofEpochMilli(e.end).atZone(zone).toLocalDateTime()
        val startsToday = begin.toLocalDate() == day
        val endsToday = end.toLocalDate() == day || (end.toLocalDate() == day.plusDays(1) && end.toLocalTime() == LocalTime.MIDNIGHT)
        return when {
            startsToday && endsToday -> if (e.begin == e.end) clockTime(begin.toLocalTime()) else "${clockTime(begin.toLocalTime())}–${clockTime(end.toLocalTime())}"
            startsToday -> "from ${clockTime(begin.toLocalTime())}"
            endsToday -> "until ${clockTime(end.toLocalTime())}"
            else -> "All day"
        }
    }
}

object AddEvent : Tool(
    name = "add_event",
    title = "Calendar",
    icon = R.drawable.ic_event,
    description = "Add an event to your calendar, on the day and at the time you say.",
    onByDefault = false,
    // Only used if the user stops it asking. On tool-check (2026-10-10), right calls scored
    // 0.10–0.77, and no wrong one got past the keywords and the title check.
    defaultRunFrom = 0.3,
    // A day or time read wrong is easy to miss, so the event is shown before it's added.
    confirmByDefault = true,
    examples = listOf("Add the dentist to my calendar tomorrow at 5 pm", "Schedule lunch with Priya on Saturday at 1"),
) {
    /** Reading too: to list the calendars, and to warn of a clash. */
    override val permissions = listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)

    override val whyAsk = "Ferb may read the day or time wrong. Without asking, events are added straight away (Undo is on the result)."

    val calendarOption = ToolOption.Calendar(key = "calendar", title = "Add events to")
    override val options = listOf(calendarOption)

    // "remind me" went to reminders (2026-10-14): the calendar keeps events.
    override val keywords = listOf("calendar", "event", "meeting", "appointment", "schedule")

    // The time as said, read by the app ([When]) from the request, with Needle 3's "when" only a
    // fallback: it doesn't know today's date, and on tool-check (2026-10-10) gave dates of its
    // own ("Friday at 8 pm" → "2026-10-08 20:00", a Thursday). Without "when", calls fell from
    // 7 of 10 to 5 and the near misses ("the meeting went well") got titles: it keeps the time
    // out of the title. "Schedule an event or a reminder" got 8–9 of 10 called, against 6–7 for
    // "Add an event"; "remind me on Friday to pay the rent" is still called only some runs, and
    // "schedule a call with the bank" goes to the calls tool (which asks first).
    override val schema = """
        {"name": "add_event",
         "description": "Schedule an event or a reminder in the calendar, such as dentist tomorrow at 5 pm",
         "parameters": {"type": "object", "properties": {
           "title": {"type": "string", "description": "What the event is, in the request's words"},
           "when": {"type": "string", "description": "The day and time, such as tomorrow at 5 pm"}},
           "required": ["title", "when"]}}
    """.trimIndent()

    override fun fromRequest(arguments: Map<String, Any?>, request: String) = arguments + (REQUEST to request)

    /**
     * The title must be mostly the request's words, and when the request's own words say nothing
     * about when (so Needle 3's "when" is used), its numbers must be ones the request says.
     */
    override fun grounded(arguments: Map<String, Any?>, request: String): Boolean {
        val title = arguments.text("title")
        val whenSaid = arguments.text("when")
        val requestSays = !When.read(request, LocalDateTime.now()).isEmpty
        return Tools.mostlySaid(title, request) && (requestSays || whenSaid == null || SaidNumbers.grounded(whenSaid, request))
    }

    /** An event worked out from a request: [begin] and [end] in epoch milliseconds (UTC midnights when [allDay]). */
    data class Planned(val title: String, val day: LocalDate, val start: LocalTime?, val endTime: LocalTime?, val endDay: LocalDate, val begin: Long, val end: Long) {
        val allDay get() = start == null
    }

    /**
     * When the event is: the request's day and time ([When]), else Needle 3's "when". A time
     * without a day is the next time it comes (today, or tomorrow if it's passed); a day without a
     * time is all day; an hour long unless an end or a length is said. Null if nothing says when.
     */
    fun plan(title: String, arguments: Map<String, Any?>, now: LocalDateTime, zone: ZoneId): Planned? {
        val fromRequest = arguments.text(REQUEST)?.let { When.read(it, now) }
        val said = fromRequest?.takeUnless { it.isEmpty } ?: arguments.text("when")?.let { When.read(it, now) }?.takeUnless { it.isEmpty } ?: return null
        val time = if (said.allDay) null else said.time
        val day = said.day ?: if (time != null && time.isBefore(now.toLocalTime())) now.toLocalDate().plusDays(1) else now.toLocalDate()
        if (time == null) {
            val begin = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            return Planned(title, day, null, null, day, begin, day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        }
        val start = day.atTime(time)
        val end = said.end?.let { day.atTime(it).let { end -> if (end.isAfter(start)) end else end.plusDays(1) } } ?: start.plusMinutes((said.length ?: 60).toLong())
        return Planned(title, day, time, end.toLocalTime(), end.toLocalDate(), start.atZone(zone).toInstant().toEpochMilli(), end.atZone(zone).toInstant().toEpochMilli())
    }

    /** "Fri 9 Oct, 17:00–18:00", or "Fri 9 Oct, all day". */
    fun describe(p: Planned, today: LocalDate): String = dayName(p.day, today) + ", " + when {
        p.start == null -> "all day"
        p.endDay != p.day -> "${clockTime(p.start)} – ${dayName(p.endDay, today)} ${clockTime(p.endTime!!)}"
        else -> "${clockTime(p.start)}–${clockTime(p.endTime!!)}"
    }

    override fun prepare(arguments: Map<String, Any?>, env: PrepareEnv): Prepared {
        val title = arguments.text("title")?.let(When::strip)?.ifBlank { null }?.replaceFirstChar { it.uppercase() }
            ?: return Prepared.Invalid("No title for the event")
        val now = env.now.toLocalDateTime()
        val zone = env.now.zone
        val planned = plan(title, arguments, now, zone) ?: return Prepared.Invalid("When? Say a day or a time, such as tomorrow at 5 pm")
        val today = now.toLocalDate()
        val whenText = describe(planned, today)
        val summary = "Add \u201c$title\u201d \u00b7 $whenText"
        val past = "That's in the past".takeIf { (planned.start?.let { planned.day.atTime(it) } ?: planned.day.plusDays(1).atStartOfDay()) <= now }

        val calendar = env.calendar.target(env.options[calendarOption.key])
            // No calendar permission: the calendar app's own new-event screen, filled in.
            ?: return Prepared.Ready(summary, detail = listOfNotNull("Opens in your calendar app, to save there", past).joinToString("\n"),
                confirm = "Open calendar", cancel = "Cancel", needs = listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)) { context -> openToSave(context, planned) }

        val clashes = planned.start?.let { env.calendar.overlapping(planned.begin, planned.end) }.orEmpty().filter { it.title != title }
        val clash = clashes.takeIf { it.isNotEmpty() }?.let { found ->
            "Clashes with " + found.take(2).joinToString(" and ") { e ->
                "${e.title} (${clockTime(Instant.ofEpochMilli(e.begin).atZone(zone).toLocalTime())})"
            } + if (found.size > 2) " and ${found.size - 2} more" else ""
        }
        val detail = listOfNotNull("In ${calendar.name}", clash, past).joinToString("\n")
        return Prepared.Ready(summary, opensApp = false, detail = detail, confirm = "Add", cancel = "Cancel") { context ->
            if (!CalendarBook.canWrite(context)) return@Ready openToSave(context, planned)
            val id = CalendarBook.add(context, calendar.id, title, planned.begin, planned.end, planned.allDay, zone)
                ?: return@Ready Outcome.Failed("The calendar didn't take the event")
            added(title, whenText, calendar.name, id, planned)
        }
    }

    /** The result of adding: "Open" shows it in the calendar app (keeping the result), "Undo" deletes it. */
    private fun added(title: String, whenText: String, calendar: String, id: Long, p: Planned): Outcome.Done =
        Outcome.Done("Added \u201c$title\u201d to $calendar: $whenText", listOf(
            FollowUp("Open") { env ->
                val opened = Tools.start(env.context, CalendarBook.viewEvent(id, p.begin, p.end), "")
                if (opened is Outcome.Failed) opened else added(title, whenText, calendar, id, p)
            },
            FollowUp("Undo") { env ->
                if (CalendarBook.delete(env.context, id)) Outcome.Done("Removed \u201c$title\u201d from $calendar")
                else Outcome.Failed("Couldn't remove it: it may have been changed or deleted already")
            },
        ))

    /** The calendar app's new-event screen with the event filled in; all-day ones from local midnight, as that screen expects. */
    private fun openToSave(context: Context, p: Planned): Outcome {
        val (begin, end) = if (p.allDay) {
            val zone = ZoneId.systemDefault()
            p.day.atStartOfDay(zone).toInstant().toEpochMilli() to p.day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        } else p.begin to p.end
        return Tools.start(context, CalendarBook.insertIntent(p.title, begin, end, p.allDay), "Event opened in your calendar app, to save there")
    }
}

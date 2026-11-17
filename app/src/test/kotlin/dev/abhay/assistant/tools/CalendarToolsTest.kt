package dev.abhay.assistant.tools

import com.google.common.truth.Truth.assertThat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.Test

class CalendarToolsTest {
    private val india = ZoneId.of("Asia/Kolkata")
    /** Thursday 8 October 2026, 10:00 in India. */
    private val now = ZonedDateTime.of(2026, 10, 8, 10, 0, 0, 0, india)
    private val today = now.toLocalDate()

    private fun at(day: Int, hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 10, day, hour, minute).atZone(india).toInstant().toEpochMilli()
    private fun utcMidnight(day: Int) = LocalDate.of(2026, 10, day).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private val calendars = listOf(
        CalendarInfo(1, "On this phone", "", writable = true, local = true),
        CalendarInfo(4, "Holidays in India", "me@example.com", writable = false),
        CalendarInfo(7, "Family", "me@example.com", writable = true),
        CalendarInfo(9, "me@example.com", "me@example.com", writable = true, primary = true),
    )
    private val events = listOf(
        CalendarEvent(1, "Dussehra", utcMidnight(9), utcMidnight(10), allDay = true, calendar = "Holidays in India"),
        CalendarEvent(2, "Standup", at(9, 10), at(9, 10, 30)),
        CalendarEvent(3, "Gym", at(9, 17, 30), at(9, 18, 30), location = "Cult, HSR"),
        CalendarEvent(4, "Night train", at(8, 22), at(9, 6)),
    )
    private val book = CalendarBook({ calendars }, { from, to -> events.filter { it.begin < to && it.end > from } }, india)

    private fun env(calendar: CalendarBook = book, options: Map<String, String> = emptyMap()) = PrepareEnv(calendar = calendar, now = now, options = options)
    /** Needle 3's arguments with the request they came from, prepared as the app prepares them ([prepareSaid]). */
    private class Asked(val request: String, val arguments: Array<out Pair<String, Any?>>)
    private fun Tool.prepare(asked: Asked, env: PrepareEnv) = prepareSaid(asked.request, env, *asked.arguments)
    private fun args(request: String, vararg pairs: Pair<String, Any?>) = Asked(request, pairs)

    @Test
    fun anEventWithTheSameTitleAtTheSameTimeIsShown() {
        // Review P2-81: the clash check left out events with the same title, so a doubled Gym went in silently.
        val ready = AddEvent.prepare(args("gym tomorrow at 5:30 pm", "title" to "Gym", "when" to "tomorrow at 5:30 pm"), env()) as Prepared.Ready
        assertThat(ready.detail).contains("Gym (17:30)")
    }

    @Test
    fun allDayEventsAreOnTheirUtcDatesNotTheHoursTheyOverlap() {
        // In India, Dussehra (9 Oct, UTC) runs from 05:30 on the 9th to 05:30 on the 10th.
        assertThat(book.day(LocalDate.of(2026, 10, 9))!!.map { it.title }).containsExactly("Dussehra", "Night train", "Standup", "Gym").inOrder()
        assertThat(book.day(LocalDate.of(2026, 10, 10))!!.map { it.title }).isEmpty()
        assertThat(CalendarBook.NONE.day(today)).isNull()
    }

    @Test
    fun newEventsGoToTheChosenCalendarElseTheAccountsOwn() {
        assertThat(book.target(null)!!.id).isEqualTo(9)
        assertThat(book.target("7")!!.id).isEqualTo(7)
        // One that can't take events, or is gone: the account's own.
        assertThat(book.target("4")!!.id).isEqualTo(9)
        assertThat(book.target("42")!!.id).isEqualTo(9)
        assertThat(CalendarBook({ calendars.take(1) }, { _, _ -> null }).target(null)!!.id).isEqualTo(1)
        assertThat(CalendarBook.NONE.target(null)).isNull()
    }

    @Test
    fun theAgendaShowsTheRequestsDay() {
        fun summary(request: String, day: String) = (ShowAgenda.prepare(args(request, "day" to day), env()) as Prepared.Ready).summary
        assertThat(summary("what's on tomorrow", "today")).isEqualTo("Show Fri 9 Oct")
        // The request names no day: today, whatever Needle 3 gave.
        assertThat(summary("am I free then", "Friday")).isEqualTo("Show Thu 8 Oct")
        // An agenda can look back.
        assertThat(summary("what was on 2 Oct", "today")).isEqualTo("Show Fri 2 Oct")
    }

    @Test
    fun anAgendaListsTimesAndPlaces() {
        val day = LocalDate.of(2026, 10, 9)
        val agenda = ShowAgenda.agenda(day, today, book.day(day)!!, india)
        assertThat(agenda.message).isEqualTo("Tomorrow, Fri 9 Oct: 4 events")
        assertThat(agenda.lines.map { "${it.lead} ${it.text}" })
            .containsExactly("All day Dussehra", "until 06:00 Night train", "10:00–10:30 Standup", "17:30–18:30 Gym").inOrder()
        assertThat(agenda.lines.last().detail).isEqualTo("Cult, HSR")
        assertThat(agenda.actions.map { it.label }).containsExactly("Open calendar")
        assertThat(ShowAgenda.agenda(today.plusDays(2), today, emptyList(), india).message).isEqualTo("Nothing on Sat 10 Oct")
        assertThat(ShowAgenda.agenda(today, today, emptyList(), india).message).isEqualTo("Today, Thu 8 Oct: nothing on")
    }

    @Test
    fun anEventIsShownBeforeItsAdded() {
        val ready = AddEvent.prepare(args("Dentist tomorrow at 5 pm", "title" to "dentist", "when" to "tomorrow at 5 pm"), env()) as Prepared.Ready
        assertThat(ready.summary).isEqualTo("Add \u201cDentist\u201d \u00b7 Fri 9 Oct, 17:00–18:00")
        assertThat(ready.detail).isEqualTo("In me@example.com\nClashes with Gym (17:30)")
        assertThat(ready.confirm).isEqualTo("Add")
        assertThat(ready.opensApp).isFalse()
        assertThat(AddEvent.confirmByDefault).isTrue()
    }

    @Test
    fun theTitleLosesTheWhenAndTheTimeComesFromTheRequest() {
        // Needle 3 left the time in the title, and got the hour wrong in "when".
        val ready = AddEvent.prepare(args("lunch with Priya on Saturday at 1", "title" to "lunch with Priya on Saturday at 1", "when" to "Saturday 11 am"),
            env(options = mapOf("calendar" to "7"))) as Prepared.Ready
        assertThat(ready.summary).isEqualTo("Add \u201cLunch with Priya\u201d \u00b7 Sat 10 Oct, 13:00–14:00")
        assertThat(ready.detail).isEqualTo("In Family")
    }

    @Test
    fun aClashIsPointedOut() {
        val ready = AddEvent.prepare(args("call with the bank tomorrow at 10:15", "title" to "call with the bank", "when" to "tomorrow at 10:15"), env()) as Prepared.Ready
        assertThat(ready.detail).isEqualTo("In me@example.com\nClashes with Standup (10:00)")
    }

    @Test
    fun aDayWithoutATimeIsAtNineAndATimeWithoutADayTheNextOne() {
        fun summary(request: String, title: String) = (AddEvent.prepare(args(request, "title" to title, "when" to ""), env()) as Prepared.Ready).summary
        // An event with a day and no time is all day (the user's decision, 2026-11-17, review P2-77).
        assertThat(summary("add mum's birthday on 12 oct to my calendar", "Mum's birthday")).isEqualTo("Add \u201cMum's birthday\u201d \u00b7 Mon 12 Oct, all day")
        assertThat(summary("add the team offsite today to my calendar", "Team offsite")).endsWith("all day")
        // 9 am has passed (it's 10:00): 9 pm.
        assertThat(summary("meeting at 9", "meeting")).isEqualTo("Add \u201cMeeting\u201d \u00b7 Thu 8 Oct, 21:00–22:00")
        assertThat(summary("meeting at 4", "meeting")).isEqualTo("Add \u201cMeeting\u201d \u00b7 Thu 8 Oct, 16:00–17:00")
        assertThat(summary("study session tomorrow at 4 for 2 hours", "study session")).isEqualTo("Add \u201cStudy session\u201d \u00b7 Fri 9 Oct, 16:00–18:00")
        assertThat(summary("party on saturday from 10 pm to 1 am", "party")).isEqualTo("Add \u201cParty\u201d \u00b7 Sat 10 Oct, 22:00 – Sun 11 Oct 01:00")
    }

    @Test
    fun needle3sWhenNeverDecides() {
        val asks = AddEvent.prepare(args("put the dentist in my calendar", "title" to "dentist", "when" to "tomorrow at 5 pm"), env()) as Prepared.Invalid
        assertThat(asks.reason).isEqualTo("When? Say a day or a time, such as tomorrow at 5 pm")
    }

    @Test
    fun withoutTheCalendarPermissionTheCalendarAppSavesIt() {
        val ready = AddEvent.prepare(args("dentist tomorrow at 5", "title" to "dentist", "when" to "tomorrow at 5"), env(CalendarBook.NONE)) as Prepared.Ready
        assertThat(ready.confirm).isEqualTo("Open calendar")
        assertThat(ready.opensApp).isTrue()
    }

    @Test
    fun aTitleTheRequestDoesntSayAsksFirst() {
        assertThat(AddEvent.grounded(mapOf("title" to "Dentist", "when" to "tomorrow at 5"), "dentist tomorrow at 5")).isTrue()
        assertThat(AddEvent.grounded(mapOf("title" to "Team offsite", "when" to "tomorrow"), "dentist tomorrow")).isFalse()
    }
}

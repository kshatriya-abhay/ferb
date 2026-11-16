package dev.abhay.assistant.tools

import com.google.common.truth.Truth.assertThat
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Test

class DateGateTest {
    /** Wednesday 4 November 2026, 10:00. */
    private val now = LocalDateTime.of(2026, 11, 4, 10, 0)
    private val zoned = ZonedDateTime.of(now, ZoneId.of("Asia/Kolkata"))

    @Test
    fun everyReasonToAskIsKept() {
        // Review P2-43: the disagreement's reason replaced the earlier one, so a repeating alarm could be
        // confirmed without the user hearing it won't repeat.
        val ask = DateGate.apply(SetAlarm.name, emptyMap(), "wake me up every day at 7", "Set an alarm for 8 am every day", zoned).time!!.ask!!
        assertThat(ask).contains("repeating alarms aren't supported yet")
        assertThat(ask).contains("your words say")
        // One reason alone reads as before.
        assertThat(DateGate.apply(SetAlarm.name, emptyMap(), "wake me up every day at 7", null, zoned).time!!.ask).isEqualTo("repeating alarms aren't supported yet")
    }

    @Test
    fun anEventsSpanGivesItsStartAndItsLength() {
        // Review P2-33: the start and the length came from two readers; the length was right, the start wasn't.
        fun event(said: String) = DateGate.apply(AddEvent.name, mapOf("title" to "x"), said, null, zoned).time!!.let { it.at to it.lengthMinutes }
        assertThat(event("kal 6 se 8 baje party")).isEqualTo(LocalDateTime.of(2026, 11, 5, 18, 0) to 120)
        assertThat(event("meeting tomorrow from 2 to 4")).isEqualTo(LocalDateTime.of(2026, 11, 5, 14, 0) to 120)
        assertThat(event("lunch tomorrow 11 to 1 pm")).isEqualTo(LocalDateTime.of(2026, 11, 5, 11, 0) to 120)
        assertThat(event("dinner tomorrow 7 to 9 pm")).isEqualTo(LocalDateTime.of(2026, 11, 5, 19, 0) to 120)
    }

    @Test
    fun aTimersLengthReadsCompoundNumberWords() {
        // Review P2-32: "twenty five minutes" was read as "five minutes".
        assertThat(DateGate.read(SetTimer.name, "set a timer for twenty five minutes", now)).isEqualTo(DateGate.Read.Length(1500))
        assertThat(DateGate.read(SetTimer.name, "timer for forty-five seconds", now)).isEqualTo(DateGate.Read.Length(45))
        assertThat(DateGate.read(SetTimer.name, "timer for one hour and thirty five minutes", now)).isEqualTo(DateGate.Read.Length(5700))
        assertThat(Durations.parse("twenty five minutes")).isEqualTo(1500)
        // The rewrite's "25" is a number the request said.
        assertThat(SaidNumbers.grounded("25 minutes", "set a timer for twenty five minutes")).isTrue()
    }

    @Test
    fun theAgendaCanLookBack() {
        // Review P2-38: the gate's day rolled forward, so "yesterday" showed today and "2 nov" next year.
        fun day(said: String) = DateGate.read(ShowAgenda.name, said, now)
        assertThat(day("what did I have yesterday")).isEqualTo(DateGate.Read.Day(java.time.LocalDate.of(2026, 11, 3)))
        assertThat(day("what was on 2 nov")).isEqualTo(DateGate.Read.Day(java.time.LocalDate.of(2026, 11, 2)))
        assertThat(day("what's on tomorrow")).isEqualTo(DateGate.Read.Day(java.time.LocalDate.of(2026, 11, 5)))
        assertThat(day("what's on my calendar")).isEqualTo(DateGate.Read.Day(java.time.LocalDate.of(2026, 11, 4)))
    }

    @Test
    fun aReminderKeepsEveryRepeatItsWordsSay() {
        // Review P2-37: "every year" and "each day" were lost, so a one-off was saved.
        fun repeat(said: String) = DateGate.apply(AddReminder.name, emptyMap(), said, null, zoned).time?.repeat
        assertThat(repeat("remind me every year on 5 march to renew the insurance")).isEqualTo(dev.abhay.assistant.reminders.Repeat.Yearly)
        assertThat(repeat("remind me each day at 8 pm to take my pills")).isEqualTo(dev.abhay.assistant.reminders.Repeat.Daily)
        assertThat(repeat("remind me every month on the 1st to pay rent")).isEqualTo(dev.abhay.assistant.reminders.Repeat.Monthly)
        // A yearly day already passed today comes back next year, not tomorrow.
        val birthday = DateGate.apply(AddReminder.name, emptyMap(), "remind me every year on 4 november at 9 am to call Riya", null, zoned).time!!
        assertThat(birthday.at).isEqualTo(LocalDateTime.of(2027, 11, 4, 9, 0))
    }

    @Test
    fun anEventsSpanIsItsLength() {
        assertThat(DateGate.span("dinner 6 to 8 pm tomorrow", now)).isEqualTo(120)
        assertThat(DateGate.span("meeting from 3 till 5 on friday", now)).isEqualTo(120)
        assertThat(DateGate.span("class 10-11:30", now)).isEqualTo(90)
        assertThat(DateGate.span("kal 6 se 8 baje party", now)).isEqualTo(120)
        assertThat(DateGate.span("कल शाम 6 से 8 बजे पार्टी", now)).isEqualTo(120)
        assertThat(DateGate.span("standup at 9 for 30 minutes", now)).isEqualTo(30)
        assertThat(DateGate.span("dentist tomorrow at 5", now)).isNull()
    }

    @Test
    fun theSpanReachesTheEventsArguments() {
        val call = mapOf<String, Any?>("title" to "dinner", "when" to "tomorrow 6 pm")
        val got = DateGate.apply(AddEvent.name, call, "dinner tomorrow from 6 to 8 pm", "Add dinner tomorrow from 6 to 8 pm", zoned)
        assertThat(got.agreed).isTrue()
        assertThat(got.time?.at?.toString()).isEqualTo("2026-11-05T18:00")
        assertThat(got.time?.lengthMinutes).isEqualTo(120)
        val planned = AddEvent.plan("Dinner", got.arguments, now, ZoneId.of("Asia/Kolkata"), got.time)!!
        assertThat(planned.start.toString() to planned.endTime.toString()).isEqualTo("18:00" to "20:00")
        // No span said: an hour, as before.
        val hour = DateGate.apply(AddEvent.name, call, "dinner tomorrow at 6 pm", null, zoned)
        assertThat(AddEvent.plan("Dinner", hour.arguments, now, ZoneId.of("Asia/Kolkata"), hour.time)!!.endTime.toString()).isEqualTo("19:00")
    }

    @Test
    fun aTimeInsideTheContentIsntTheCalls() {
        // A reminder for 6 whose subject has a time of its own.
        val said = "remind me at 6 to tell Priya the bus comes at 8:40 tomorrow"
        val content = "tell Priya the bus comes at 8:40 tomorrow"
        val command = "Remind me at 6 pm to \"tell Priya the bus comes at 8:40 tomorrow\""
        val got = DateGate.apply(AddReminder.name, mapOf("text" to content), said, command, zoned, content)
        assertThat(got.agreed).isTrue()
        assertThat(got.time?.at?.toString()).isEqualTo("2026-11-04T18:00")
        // A message with a time in it: no time outside it, so none is read (the time guard stays quiet).
        val text = DateGate.apply(AddReminder.name, emptyMap(), "text Arjun I'm leaving at 5", "Send a text to Arjun: \"I'm leaving at 5\"", zoned, "I'm leaving at 5")
        assertThat(text.said).isInstanceOf(DateGate.Read.Ask::class.java)
        assertThat(text.rewritten).isInstanceOf(DateGate.Read.Ask::class.java)
        val hindi = DateGate.apply(AddReminder.name, emptyMap(), "Meena ko message karo ki dinner kal 8 baje hai", null, zoned, "dinner kal 8 baje hai")
        assertThat(hindi.said).isInstanceOf(DateGate.Read.Ask::class.java)
    }

    @Test
    fun withoutAContentSpanTimesAreReadAsBefore() {
        val said = "remind me tomorrow at 7 to water the plants"
        assertThat(DateGate.outside(said, null)).isEqualTo(said)
        assertThat(DateGate.outside(said, "")).isEqualTo(said)
        // Content not found in the words: unchanged.
        assertThat(DateGate.outside(said, "feed the dog")).isEqualTo(said)
        assertThat(DateGate.outside(said, "water the plants")).isEqualTo("remind me tomorrow at 7 to")
        val got = DateGate.apply(AddReminder.name, emptyMap(), said, null, zoned)
        assertThat(got.time?.at?.toString()).isEqualTo("2026-11-05T07:00")
    }
}

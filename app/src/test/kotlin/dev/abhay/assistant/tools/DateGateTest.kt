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
        assertThat(got.arguments[TimeArgs.AT]).isEqualTo("2026-11-05T18:00")
        assertThat(got.arguments[TimeArgs.LENGTH]).isEqualTo(120)
        val planned = AddEvent.plan("Dinner", got.arguments, now, ZoneId.of("Asia/Kolkata"))!!
        assertThat(planned.start.toString() to planned.endTime.toString()).isEqualTo("18:00" to "20:00")
        // No span said: an hour, as before.
        val hour = DateGate.apply(AddEvent.name, call, "dinner tomorrow at 6 pm", null, zoned)
        assertThat(AddEvent.plan("Dinner", hour.arguments, now, ZoneId.of("Asia/Kolkata"))!!.endTime.toString()).isEqualTo("19:00")
    }
}

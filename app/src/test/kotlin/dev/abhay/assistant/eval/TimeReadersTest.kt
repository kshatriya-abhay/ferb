package dev.abhay.assistant.eval

import com.google.common.truth.Truth.assertThat
import java.time.LocalDateTime
import org.junit.Test

class TimeReadersTest {

    @Test
    fun timesOfDayAsTheModelWritesThem() {
        // Forms seen from Needle 3 on the desktop check, and a few more.
        val cases = mapOf(
            "8:00 AM" to (8 to 0), "08:00" to (8 to 0), "9:00" to (9 to 0), "8" to (8 to 0), "6:45" to (6 to 45),
            "7 pm" to (19 to 0), "7:30 p.m." to (19 to 30), "12 am" to (0 to 0), "12 pm" to (12 to 0), "19:30" to (19 to 30),
            "6.45" to (6 to 45), "noon" to (12 to 0), "midnight" to (0 to 0),
        )
        cases.forEach { (text, expected) -> assertThat(TimeOfDay.parse(text)).isEqualTo(expected) }
        listOf("25:00", "13 pm", "8:75", "tomorrow", "").forEach { assertThat(TimeOfDay.parse(it)).isNull() }
        // As the computer's model writes them for spoken requests (2026-10-12: "5 o'clock" was refused).
        val spoken = mapOf("5 o'clock" to (5 to 0), "five o'clock" to (5 to 0), "5 baje" to (5 to 0), "tomorrow at 6" to (6 to 0),
            "6 am tomorrow" to (6 to 0), "7 in the evening" to (19 to 0), "5 in the morning" to (5 to 0), "9 at night" to (21 to 0),
            "12 at night" to (0 to 0), "3 in the afternoon" to (15 to 0), "subah 5" to (5 to 0), "shaam 6 baje" to (18 to 0),
            "7:30 in the evening" to (19 to 30), "6 pm tonight" to (18 to 0), "raat 2 baje" to (2 to 0), "raat 10 baje" to (22 to 0),
            // As Ferb Link leaves the model's own words for the time of day.
            "11 o'clock at night" to (23 to 0), "7 o'clock in the morning" to (7 to 0), "3 o'clock in the afternoon" to (15 to 0))
        spoken.forEach { (text, expected) -> assertThat(TimeOfDay.parse(text)).isEqualTo(expected) }
        // am and pm however they're written (2026-10-24: "10 a.m." wasn't a time of day).
        val written = mapOf("10 a.m." to (10 to 0), "10 A.M." to (10 to 0), "10am." to (10 to 0), "10 a m" to (10 to 0), "10a.m" to (10 to 0),
            "7:15 P.M." to (19 to 15), "7 p. m." to (19 to 0), "12 a.m." to (0 to 0))
        written.forEach { (text, expected) -> assertThat(TimeOfDay.parse(text)).isEqualTo(expected) }
    }

    @Test
    fun remindersTimesAsSaid() {
        val now = LocalDateTime.of(2026, 10, 14, 13, 0) // a Wednesday afternoon
        fun at(text: String) = ReminderWords.time(text, now)
        assertThat(at("remind me in 20 minutes to check the oven")).isEqualTo(now.plusMinutes(20))
        assertThat(at("in an hour")).isEqualTo(now.plusHours(1))
        assertThat(at("tomorrow at 9")).isEqualTo(LocalDateTime.of(2026, 10, 15, 9, 0))
        assertThat(at("on Friday to pay the rent")).isEqualTo(LocalDateTime.of(2026, 10, 16, 9, 0)) // a day alone: 09:00
        assertThat(at("at 6 pm")).isEqualTo(LocalDateTime.of(2026, 10, 14, 18, 0))
        assertThat(at("at 8 am")).isEqualTo(LocalDateTime.of(2026, 10, 15, 8, 0)) // passed today: tomorrow
        assertThat(at("to call mum")).isNull()
    }
}

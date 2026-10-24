package dev.abhay.assistant.tools

import com.google.common.truth.Truth.assertThat
import dev.abhay.assistant.tools.DateResolver.Kind
import dev.abhay.assistant.tools.DateResolver.Resolved
import dev.abhay.assistant.tools.DateResolver.Rule
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Test

/** The rules in tool-check/datetime.txt's header, on a clock and wording that aren't in that set. */
class DateResolverTest {
    /** Wednesday 4 November 2026, 10:00. */
    private val now = LocalDateTime.of(2026, 11, 4, 10, 0)

    private fun at(text: String, kind: Kind = Kind.Reminder, clock: LocalDateTime = now) = DateResolver.resolve(text, kind, clock)
    private fun on(month: Int, day: Int, hour: Int, minute: Int = 0, year: Int = 2026) = Resolved.At(LocalDateTime.of(year, month, day, hour, minute))

    @Test
    fun weekdays() {
        assertThat(at("remind me next wednesday to pay rent")).isEqualTo(on(11, 11, 9))
        assertThat(at("remind me on wednesday at 5 pm to pay rent")).isEqualTo(on(11, 4, 17))
        // Its default time has passed: a week on.
        assertThat(at("remind me wednesday to pay rent")).isEqualTo(on(11, 11, 9))
        assertThat(at("remind me friday to pay rent")).isEqualTo(on(11, 6, 9))
        assertThat(at("remind me next friday to pay rent")).isEqualTo(on(11, 6, 9))
    }

    @Test
    fun bareHours() {
        // No day: the next of 3:00 and 15:00.
        assertThat(at("remind me at 3 to stretch")).isEqualTo(on(11, 4, 15))
        assertThat(at("remind me at 11 to stretch")).isEqualTo(on(11, 4, 11))
        // With a later day: 7-11 am, 12 noon, 1-6 pm.
        assertThat(at("remind me tomorrow at 8 to stretch")).isEqualTo(on(11, 5, 8))
        assertThat(at("remind me tomorrow at 4 to stretch")).isEqualTo(on(11, 5, 16))
        // Today: whichever is still ahead.
        assertThat(at("remind me at 9 today to stretch")).isEqualTo(on(11, 4, 21))
    }

    @Test
    fun partsOfTheDayAndClockWords() {
        assertThat(at("remind me this evening to cook")).isEqualTo(on(11, 4, 18))
        assertThat(at("remind me tomorrow afternoon to cook")).isEqualTo(on(11, 5, 14))
        assertThat(at("remind me tomorrow evening at 7 to cook")).isEqualTo(on(11, 5, 19))
        assertThat(at("remind me at half past 4 tomorrow to cook")).isEqualTo(on(11, 5, 16, 30))
        assertThat(at("remind me at noon to cook")).isEqualTo(on(11, 4, 12))
        assertThat(at("remind me at midnight to cook")).isEqualTo(on(11, 5, 0))
    }

    @Test
    fun lengthsFromNow() {
        assertThat(at("remind me in 45 minutes to check the oven")).isEqualTo(on(11, 4, 10, 45))
        assertThat(at("remind me in 2 days to check the oven")).isEqualTo(on(11, 6, 9))
    }

    @Test
    fun dates() {
        assertThat(at("remind me on the 10th to renew")).isEqualTo(on(11, 10, 9))
        // Passed this month: next month's.
        assertThat(at("remind me on the 2nd to renew")).isEqualTo(on(12, 2, 9))
        // d/m, and across the year end.
        assertThat(at("remind me on 3/1 to renew")).isEqualTo(on(1, 3, 9, year = 2027))
        // "for 12/11" is a date, not 12 o'clock (as a rewrite says it, 2026-10-24).
        assertThat(at("set a reminder for 12/11 to renew")).isEqualTo(on(11, 12, 9))
        assertThat(at("remind me on 2026-11-20 at 8 pm to renew")).isEqualTo(on(11, 20, 20))
        // A date that doesn't exist asks; it doesn't throw.
        assertThat(at("remind me on 2026-02-30 at 9 to renew")).isInstanceOf(Resolved.Ask::class.java)
        assertThat(at("remind me on 31/11 to renew")).isInstanceOf(Resolved.Ask::class.java)
    }

    @Test
    fun hindi() {
        assertThat(at("kal subah saat baje yaad dilana gym jaana hai")).isEqualTo(on(11, 5, 7))
        assertThat(at("parso shaam 6 baje yaad dilana")).isEqualTo(on(11, 6, 18))
        assertThat(at("agle budhvaar yaad dilana")).isEqualTo(on(11, 11, 9))
        assertThat(at("kal saade paanch baje yaad dilana")).isEqualTo(on(11, 5, 17, 30))
    }

    @Test
    fun asksRatherThanGuesses() {
        assertThat(at("remind me to buy milk")).isInstanceOf(Resolved.Ask::class.java)
        assertThat(at("remind me today to buy milk")).isInstanceOf(Resolved.Ask::class.java)
        assertThat(at("remind me today at 8 am to buy milk")).isInstanceOf(Resolved.Ask::class.java)
        assertThat(at("set an alarm for tomorrow", Kind.Alarm)).isInstanceOf(Resolved.Ask::class.java)
        assertThat(at("set an alarm for tomorrow morning", Kind.Alarm)).isInstanceOf(Resolved.Ask::class.java)
        // Tonight, said after 20:00 with no time.
        assertThat(at("remind me tonight to lock up", clock = LocalDateTime.of(2026, 11, 4, 21, 0))).isInstanceOf(Resolved.Ask::class.java)
    }

    @Test
    fun alarmsAndRepeats() {
        assertThat(at("wake me up at 6:30 am", Kind.Alarm)).isEqualTo(on(11, 5, 6, 30))
        assertThat(at("remind me every day at 8 pm to take my pills")).isEqualTo(Resolved.At(LocalDateTime.of(2026, 11, 4, 20, 0), Rule.Daily))
        assertThat((at("remind me every weekday at 9 am to stand up") as Resolved.At).rule).isEqualTo(Rule.Weekdays)
    }

    /** Found by scoring on tool-check/times.txt (2026-10-24); the wording here is new. */
    @Test
    fun timesTxtBugs() {
        // Hindi lengths.
        assertThat(at("15 minute mein yaad dilana")).isEqualTo(on(11, 4, 10, 15))
        assertThat(at("do ghante mein yaad dilana")).isEqualTo(on(11, 4, 12))
        assertThat(at("aadhe ghante mein yaad dilana")).isEqualTo(on(11, 4, 10, 30))
        // Two parts to a length.
        assertThat(at("remind me in 1 hour 10 minutes to leave")).isEqualTo(on(11, 4, 11, 10))
        assertThat(at("remind me in 3 hours and 5 min to leave")).isEqualTo(on(11, 4, 13, 5))
        // Repeats said with a part of the day, or har.
        assertThat(at("remind me every evening to walk")).isEqualTo(Resolved.At(LocalDateTime.of(2026, 11, 4, 18, 0), Rule.Daily))
        assertThat(at("har shukravar 7 baje yaad dilana")).isEqualTo(Resolved.At(LocalDateTime.of(2026, 11, 6, 7, 0), Rule.Weekly))
        // Not one day: ask.
        assertThat(at("remind me at the weekend to clean")).isInstanceOf(Resolved.Ask::class.java)
        assertThat(at("remind me on tuesday and thursday at 6 to swim")).isInstanceOf(Resolved.Ask::class.java)
        // Today's weekday with a bare hour still ahead today (the said-day rule: 4 is 4 pm).
        assertThat(at("meeting on wednesday at 4", Kind.Event)).isEqualTo(on(11, 4, 16))
        assertThat(at("meeting on wednesday at 9", Kind.Event)).isEqualTo(on(11, 11, 9))
    }

    /** Waking words: a bare hour is the morning's (set v2); a part of the day still decides. */
    @Test
    fun wakingWords() {
        assertThat(at("wake me at 9", Kind.Alarm)).isEqualTo(on(11, 5, 9))
        assertThat(at("I have to get up at quarter to 6 on friday", Kind.Alarm)).isEqualTo(on(11, 6, 5, 45))
        assertThat(at("parso 4 baje jaga dena", Kind.Alarm)).isEqualTo(on(11, 6, 4))
        assertThat(at("shaam 5 baje utha dena", Kind.Alarm)).isEqualTo(on(11, 4, 17))
        // No waking word: the frozen rule.
        assertThat(at("alarm at 9", Kind.Alarm)).isEqualTo(on(11, 4, 21))
    }

    /** A rewrite with the calendar says both; the date wins, even when they disagree. */
    @Test
    fun aDateWinsOverItsWeekday() {
        assertThat(at("remind me on Friday 13 November at 10 to book tickets")).isEqualTo(on(11, 13, 10))
        assertThat(at("remind me on Monday 6 November to book tickets")).isEqualTo(on(11, 6, 9))
    }

    @Test
    fun agendaDays() {
        assertThat(at("what's on tomorrow", Kind.Agenda)).isEqualTo(Resolved.Day(LocalDate.of(2026, 11, 5)))
        assertThat(at("what do I have on saturday", Kind.Agenda)).isEqualTo(Resolved.Day(LocalDate.of(2026, 11, 7)))
        assertThat(at("what's on my calendar", Kind.Agenda)).isEqualTo(Resolved.Day(LocalDate.of(2026, 11, 4)))
    }
}

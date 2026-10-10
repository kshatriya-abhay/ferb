package dev.abhay.assistant.tools

import com.google.common.truth.Truth.assertThat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.Test

class WhenTest {
    /** Thursday 8 October 2026, 10:00. */
    private val now = LocalDateTime.of(2026, 10, 8, 10, 0)
    private val today = now.toLocalDate()

    private fun day(text: String) = When.day(text, today)
    private fun read(text: String) = When.read(text, now)
    private fun date(month: Int, day: Int, year: Int = 2026) = LocalDate.of(year, month, day)
    private fun time(hour: Int, minute: Int = 0) = LocalTime.of(hour, minute)

    @Test
    fun relativeDays() {
        assertThat(day("what's on today")).isEqualTo(today)
        assertThat(day("What do I have tomorrow?")).isEqualTo(date(10, 9))
        assertThat(day("am I free the day after tomorrow")).isEqualTo(date(10, 10))
        assertThat(day("what did I have yesterday")).isEqualTo(date(10, 7))
        assertThat(day("plans for tonight")).isEqualTo(today)
        assertThat(day("in 3 days")).isEqualTo(date(10, 11))
        assertThat(day("in a week")).isEqualTo(date(10, 15))
        assertThat(day("what's on my calendar")).isNull()
    }

    @Test
    fun weekdaysAreTheComingOne() {
        assertThat(day("am I free on Friday")).isEqualTo(date(10, 9))
        assertThat(day("next friday")).isEqualTo(date(10, 9))
        assertThat(day("this Monday")).isEqualTo(date(10, 12))
        assertThat(day("tues")).isEqualTo(date(10, 13))
        // Today's weekday is today, but "next" on the day itself is a week on.
        assertThat(day("thursday")).isEqualTo(today)
        assertThat(day("next thursday")).isEqualTo(date(10, 15))
    }

    @Test
    fun wordsThatStartLikeDaysOrMonthsAreNeither() {
        assertThat(day("dinner with friends")).isNull()
        assertThat(day("the wedding")).isNull()
        assertThat(day("sat in the sun")).isNull()
        assertThat(day("decent plan 5")).isNull()
        assertThat(day("monsoon trip")).isNull()
    }

    @Test
    fun datesWithMonths() {
        assertThat(day("12 oct")).isEqualTo(date(10, 12))
        assertThat(day("on the 12th of October")).isEqualTo(date(10, 12))
        assertThat(day("Oct 3rd")).isEqualTo(date(10, 3))
        assertThat(day("december 25, 2027")).isEqualTo(date(12, 25, 2027))
        assertThat(day("2026-11-02")).isEqualTo(date(11, 2))
        // Day first, as in India.
        assertThat(day("on 3/11")).isEqualTo(date(11, 3))
        assertThat(day("the 20th")).isEqualTo(date(10, 20))
    }

    @Test
    fun withoutAYearAnAgendaTakesTheNearestAndAnEventTheNext() {
        assertThat(day("3 oct")).isEqualTo(date(10, 3))
        assertThat(read("dentist on 3 oct at 5").day).isEqualTo(date(10, 3, 2027))
        assertThat(day("jan 5")).isEqualTo(date(1, 5, 2027))
        assertThat(read("party on the 2nd at 8 pm").day).isEqualTo(date(11, 2))
    }

    @Test
    fun times() {
        assertThat(read("dentist tomorrow at 5").time).isEqualTo(time(17))
        assertThat(read("standup at 9:30").time).isEqualTo(time(9, 30))
        assertThat(read("call at 10 am").time).isEqualTo(time(10))
        assertThat(read("call at 10 a.m. tomorrow").time).isEqualTo(time(10))
        assertThat(read("movie at 7pm").time).isEqualTo(time(19))
        assertThat(read("gym at 6 in the morning").time).isEqualTo(time(6))
        assertThat(read("drinks friday evening at 8").time).isEqualTo(time(20))
        assertThat(read("dinner at 8").time).isEqualTo(time(20))
        assertThat(read("team lunch at noon").time).isEqualTo(time(12))
        assertThat(read("flight at 17:45").time).isEqualTo(time(17, 45))
    }

    @Test
    fun partsOfTheDayAloneGiveAUsualTime() {
        assertThat(read("call the bank tomorrow morning").time).isEqualTo(time(9))
        assertThat(read("lunch with Priya on Saturday").time).isEqualTo(time(13))
        assertThat(read("movie tonight").let { it.day to it.time }).isEqualTo(today to time(20))
    }

    @Test
    fun numbersThatArentTimes() {
        assertThat(read("meeting with 5 people tomorrow").time).isNull()
        assertThat(read("submit 2 reports on friday").time).isNull()
        assertThat(read("review for 2 hours on monday").time).isNull()
        assertThat(read("2026-11-02 meeting").time).isNull()
        assertThat(read("on 12/10 meeting").time).isNull()
    }

    @Test
    fun rangesAndLengths() {
        read("workshop from 2 to 3:30 pm on friday").let {
            assertThat(it.time).isEqualTo(time(14))
            assertThat(it.end).isEqualTo(time(15, 30))
        }
        read("meeting 5-6pm").let { assertThat(it.time to it.end).isEqualTo(time(17) to time(18)) }
        read("class between 10 and 11 tomorrow").let { assertThat(it.time to it.end).isEqualTo(time(10) to time(11)) }
        read("brunch 11 to 1 pm").let { assertThat(it.time to it.end).isEqualTo(time(11) to time(13)) }
        assertThat(read("study at 4 for 2 hours").length).isEqualTo(120)
        assertThat(read("call at 3 for half an hour").length).isEqualTo(30)
        assertThat(read("interview at 11 for an hour").length).isEqualTo(60)
        assertThat(read("interview at 11 for 1 hour 30 minutes").length).isEqualTo(90)
        assertThat(read("2026-10-12 17:00-18:00").let { it.day to (it.time to it.end) }).isEqualTo(date(10, 12) to (time(17) to time(18)))
    }

    @Test
    fun allDay() {
        assertThat(read("offsite on friday all day").allDay).isTrue()
        assertThat(read("holiday tomorrow").let { it.allDay || it.time != null }).isFalse()
        assertThat(read("my calendar").isEmpty).isTrue()
    }

    @Test
    fun stripTakesTheWhenOutOfATitle() {
        assertThat(When.strip("Dentist tomorrow at 5")).isEqualTo("Dentist")
        assertThat(When.strip("Lunch with Priya on Saturday")).isEqualTo("Lunch with Priya")
        assertThat(When.strip("Team meeting next Monday from 2 to 3 pm")).isEqualTo("Team meeting")
        assertThat(When.strip("Call the bank tomorrow morning")).isEqualTo("Call the bank")
        assertThat(When.strip("Flight to Goa on 12th October at 6:30 am")).isEqualTo("Flight to Goa")
        assertThat(When.strip("Study for 2 hours")).isEqualTo("Study")
        assertThat(When.strip("Dinner with friends")).isEqualTo("Dinner with friends")
    }
}

package dev.abhay.assistant.reminders

import com.google.common.truth.Truth.assertThat
import java.time.LocalDateTime
import org.junit.Test

/** Repeats keep their day, and a snooze isn't lost to a launch or a boot (review P2-53, P2-54, P2-55). */
class ReminderRepeatTest {
    private var now = LocalDateTime.of(2026, 11, 16, 9, 0)
    private val book = ReminderBook(MemoryStore()) { now }

    @Test
    fun aMonthlyReminderOnThe31stIsBackOnThe31stAfterFebruary() {
        // P2-53: each time was one period after the last, so February's 28th stuck.
        now = LocalDateTime.of(2027, 1, 31, 9, 0)
        val id = book.add("Pay the rent", now, Repeat.Monthly).id
        book.fire(id) { true }
        assertThat(book.all().single().at).isEqualTo(LocalDateTime.of(2027, 2, 28, 9, 0))
        now = book.all().single().at
        book.fire(id) { true }
        assertThat(book.all().single().at).isEqualTo(LocalDateTime.of(2027, 3, 31, 9, 0))
    }

    @Test
    fun aYearlyReminderOnFeb29IsBackOnFeb29InTheNextLeapYear() {
        now = LocalDateTime.of(2028, 2, 29, 9, 0)
        val id = book.add("Leap day party", now, Repeat.Yearly).id
        book.fire(id) { true }
        repeat(3) {
            now = book.all().single().at
            book.fire(id) { true }
        }
        assertThat(book.all().single().at).isEqualTo(LocalDateTime.of(2032, 2, 29, 9, 0))
    }

    @Test
    fun aDailyRepeatSnoozedBeforeALaunchKeepsTomorrow() {
        // P2-54: catchUp picked it by the snooze's time and moved it on again, skipping tomorrow.
        val id = book.add("Take the pills", now, Repeat.Daily).id
        book.fire(id) { true }
        book.snooze(id)
        now = LocalDateTime.of(2026, 11, 16, 9, 30)
        book.catchUp()
        assertThat(book.all().single().at).isEqualTo(LocalDateTime.of(2026, 11, 17, 9, 0))
    }

    @Test
    fun aSnoozeDueWhileThePhoneWasOffShowsLate() {
        // P2-55: a one-off already shown was never missed, so its snooze never came.
        val id = book.add("Call the plumber", now).id
        book.fire(id) { true }
        book.snooze(id)
        now = LocalDateTime.of(2026, 11, 16, 9, 30)
        val shown = mutableListOf<String>()
        book.catchUp()
        book.showMissed { shown += it.title; true }
        assertThat(shown).containsExactly("Call the plumber")
        // Shown once: the snooze is spent.
        book.showMissed { shown += it.title; true }
        assertThat(shown).hasSize(1)
        assertThat(id).isNotEmpty()
    }
}

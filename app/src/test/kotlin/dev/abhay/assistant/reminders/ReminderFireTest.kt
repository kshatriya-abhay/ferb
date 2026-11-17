package dev.abhay.assistant.reminders

import com.google.common.truth.Truth.assertThat
import java.time.LocalDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Test

/**
 * A reminder's notification shows once (review P2-17): when its alarm fires while a launch shows
 * what was missed, as on a cold start from the alarm, on two threads.
 */
class ReminderFireTest {
    private val now = LocalDateTime.of(2026, 11, 16, 9, 0)

    private fun bookWithOneDueNow(): Pair<ReminderBook, String> {
        val book = ReminderBook(MemoryStore()) { now }
        return book to book.add("Call the plumber", now).id
    }

    @Test
    fun aReminderShownAsMissedIsntShownAgainWhenItsAlarmFires() {
        val (book, id) = bookWithOneDueNow()
        val shown = mutableListOf<String>()
        book.showMissed { shown += "late"; true }
        book.fire(id) { shown += "on time"; true }
        assertThat(shown).containsExactly("late")
    }

    @Test
    fun theAlarmAndTheLaunchOnTwoThreadsShowItOnce() {
        val (book, id) = bookWithOneDueNow()
        val shown = java.util.Collections.synchronizedList(mutableListOf<String>())
        val launchShowing = CountDownLatch(1)
        val alarmDone = CountDownLatch(1)
        val launch = thread {
            book.showMissed {
                launchShowing.countDown()
                alarmDone.await(500, TimeUnit.MILLISECONDS) // the alarm's thread gets every chance to run meanwhile
                shown += "late"
                true
            }
        }
        val alarm = thread {
            launchShowing.await()
            book.fire(id) { shown += "on time"; true }
            alarmDone.countDown()
        }
        launch.join(); alarm.join()
        assertThat(shown).containsExactly("late")
    }

    @Test
    fun aSnoozedReminderShowsAgain() {
        var clock = now
        val book = ReminderBook(MemoryStore()) { clock }
        val id = book.add("Call the plumber", now).id
        val shown = mutableListOf<String>()
        book.fire(id) { shown += "on time"; true }
        book.snooze(id)
        // The snooze's alarm goes off at its time.
        clock = clock.plusMinutes(ReminderBook.SNOOZE_MINUTES)
        book.fire(id) { shown += "snoozed"; true }
        assertThat(shown).containsExactly("on time", "snoozed").inOrder()
    }

    @Test
    fun aRepeatShowsOncePerOccurrenceEvenIfAStaleAlarmFiresAgain() {
        // Review P2-56: fire showed a repeat on any FIRE, so a stale alarm showed it twice.
        var clock = now
        val book = ReminderBook(MemoryStore()) { clock }
        val id = book.add("Take the pills", now, Repeat.Daily).id
        val shown = mutableListOf<LocalDateTime>()
        book.fire(id) { shown += clock; true }
        clock = clock.plusSeconds(5)
        book.fire(id) { shown += clock; true }
        assertThat(shown).containsExactly(now)
    }

    @Test
    fun aOneOffThatCouldntBeShownIsStillMissed() {
        // Review P2-58: fire marked it shown though show() failed (notifications off), so it never showed late.
        val (book, id) = bookWithOneDueNow()
        book.fire(id) { false }
        assertThat(book.missed().map { it.id }).containsExactly(id)
    }

    @Test
    fun scheduleGetsTheUpcomingReminders() {
        // Review P2-56: callers read upcoming() under the lock but scheduled outside it; schedule
        // now hands the scheduler what's upcoming (the lock itself isn't visible from here).
        val (book, _) = bookWithOneDueNow()
        val scheduled = mutableListOf<List<String>>()
        book.add("Later", now.plusHours(1))
        book.schedule { upcoming -> scheduled += upcoming.map { it.title } }
        assertThat(scheduled).containsExactly(listOf("Later"))
    }
}

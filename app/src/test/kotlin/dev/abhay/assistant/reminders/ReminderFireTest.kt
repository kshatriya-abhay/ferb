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

    private class MemoryStore : ReminderStore {
        @Volatile var saved = emptyList<Reminder>()
        override fun load() = saved
        override fun save(reminders: List<Reminder>) { saved = reminders }
    }

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
        val (book, id) = bookWithOneDueNow()
        val shown = mutableListOf<String>()
        book.fire(id) { shown += "on time"; true }
        book.snooze(id)
        book.fire(id) { shown += "snoozed"; true }
        assertThat(shown).containsExactly("on time", "snoozed").inOrder()
    }
}

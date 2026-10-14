package dev.abhay.assistant.tools

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** A tool that can't do without an Android permission says which, so the reply can ask for it there. */
class PermissionNeedsTest {
    private val noAccess = PrepareEnv(contacts = ContactBook { null }, calendar = CalendarBook({ null }, { _, _ -> null }))

    @Test
    fun callsAndMessagesNeedTheContacts() {
        listOf(
            MakeCall.prepare(mapOf("name" to "Priya"), noAccess),
            SendText.prepare(mapOf("name" to "Priya", "message" to "on my way"), noAccess),
        ).forEach { prepared ->
            assertThat((prepared as Prepared.Invalid).needs).containsExactly(Manifest.permission.READ_CONTACTS)
            assertThat(prepared.reason).contains("stay on this phone")
        }
    }

    @Test
    fun theAgendaNeedsTheCalendar() {
        val ready = ShowAgenda.prepare(mapOf("day" to "today"), noAccess) as Prepared.Ready
        val outcome = ready.run(unusedContext()) as Outcome.Failed
        assertThat(outcome.needs).containsExactly(Manifest.permission.READ_CALENDAR)
    }

    /** The agenda reads the calendar, not the Context; an empty one, without Android's constructor. */
    private fun unusedContext(): Context {
        val unsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        return unsafe.javaClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, ContextWrapper::class.java) as Context
    }

    @Test
    fun otherFailuresNeedNothing() {
        assertThat((SetAlarm.prepare(mapOf("time" to "soon"), PrepareEnv()) as Prepared.Invalid).needs).isEmpty()
    }
}

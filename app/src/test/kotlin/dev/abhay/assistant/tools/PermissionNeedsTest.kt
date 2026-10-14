package dev.abhay.assistant.tools

import android.Manifest
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Without an Android permission, a tool still does what it can (the dialer, the messages app, the
 * calendar app) and says which permission would do better, so the reply can ask for it there.
 */
class PermissionNeedsTest {
    private val noAccess = PrepareEnv(contacts = ContactBook.NONE, calendar = CalendarBook.NONE)

    @Test
    fun callsAndMessagesWithoutTheContactsUseTheDialerAndTheMessagesApp() {
        val call = MakeCall.prepare(mapOf("name" to "Priya"), noAccess) as Prepared.Ready
        assertThat(call.confirm).isEqualTo("Open dialer")
        assertThat(call.needs).containsExactly(Manifest.permission.READ_CONTACTS)
        val text = SendText.prepare(mapOf("name" to "Priya", "message" to "on my way"), noAccess) as Prepared.Ready
        assertThat(text.confirm).isEqualTo("Open messages")
        assertThat(text.detail).contains("on my way")
        assertThat(text.needs).containsExactly(Manifest.permission.READ_CONTACTS)
    }

    @Test
    fun theCalendarToolsWithoutTheCalendarUseTheCalendarApp() {
        val agenda = ShowAgenda.prepare(mapOf("day" to "today"), noAccess) as Prepared.Ready
        assertThat(agenda.confirm).isEqualTo("Open calendar")
        assertThat(agenda.needs).containsExactly(Manifest.permission.READ_CALENDAR)
        val event = AddEvent.prepare(mapOf("title" to "dentist", "when" to "tomorrow at 5 pm"), noAccess) as Prepared.Ready
        assertThat(event.confirm).isEqualTo("Open calendar")
        assertThat(event.needs).containsAtLeast(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
    }

    @Test
    fun withAccessOrForOtherReasonsNothingIsNeeded() {
        assertThat((SetAlarm.prepare(mapOf("time" to "soon"), PrepareEnv()) as Prepared.Invalid).needs).isEmpty()
        assertThat((SetAlarm.prepare(mapOf("time" to "6:45"), PrepareEnv()) as Prepared.Ready).needs).isEmpty()
    }
}

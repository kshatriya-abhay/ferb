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
    fun aCallWithoutTheCallPermissionOffersToAllowIt() {
        // Review P2-148: with calls refused or taken back, the reply says so and offers Allow, rather
        // than only a hint to the tool's page.
        val contacts = ContactBook { listOf(Contact("Priya", "+91 98765 43210")) }
        val without = MakeCall.prepare(mapOf("name" to "Priya"), PrepareEnv(contacts = contacts, granted = { it != Manifest.permission.CALL_PHONE })) as Prepared.Ready
        assertThat(without.needs).containsExactly(Manifest.permission.CALL_PHONE)
        val with = MakeCall.prepare(mapOf("name" to "Priya"), PrepareEnv(contacts = contacts)) as Prepared.Ready
        assertThat(with.needs).isEmpty()
    }

    @Test
    fun theCalendarToolsWithoutTheCalendarUseTheCalendarApp() {
        val agenda = ShowAgenda.prepareSaid("what's on today", noAccess, "day" to "today") as Prepared.Ready
        assertThat(agenda.confirm).isEqualTo("Open calendar")
        assertThat(agenda.needs).containsExactly(Manifest.permission.READ_CALENDAR)
        val event = AddEvent.prepareSaid("dentist tomorrow at 5 pm", noAccess, "title" to "dentist", "when" to "tomorrow at 5 pm") as Prepared.Ready
        assertThat(event.confirm).isEqualTo("Open calendar")
        assertThat(event.needs).containsAtLeast(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
    }

    @Test
    fun withAccessOrForOtherReasonsNothingIsNeeded() {
        assertThat((SetAlarm.prepareSaid("set an alarm soon", PrepareEnv(), "time" to "soon") as Prepared.Invalid).needs).isEmpty()
        assertThat((SetAlarm.prepareSaid("set an alarm for 6:45", PrepareEnv(), "time" to "6:45") as Prepared.Ready).needs).isEmpty()
    }
}

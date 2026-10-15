package dev.abhay.assistant

import android.Manifest
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dev.abhay.assistant.tools.AddReminder
import dev.abhay.assistant.tools.CalendarBook
import dev.abhay.assistant.tools.ContactBook
import dev.abhay.assistant.tools.MakeCall
import dev.abhay.assistant.tools.PrepareEnv
import dev.abhay.assistant.tools.Prepared
import dev.abhay.assistant.tools.SendText
import dev.abhay.assistant.tools.ShowAgenda
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The tools as the phone's permissions actually stand: with one revoked (the script runs this with
 * `pm revoke` and again after `pm grant`), the tool falls back and names what it needs; with it
 * granted, it doesn't.
 */
@RunWith(AndroidJUnit4::class)
class PermissionsDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app = context.applicationContext as AssistantApp
    private fun granted(p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    private val env = PrepareEnv(contacts = ContactBook.from(context), calendar = CalendarBook.from(context), reminders = app.reminders, granted = ::granted)

    @Test
    fun contacts() {
        val call = MakeCall.prepare(mapOf("name" to "Nobody Testperson"), env) as Prepared.Ready
        val text = SendText.prepare(mapOf("name" to "Nobody Testperson", "message" to "hi"), env) as Prepared.Ready
        if (granted(Manifest.permission.READ_CONTACTS)) {
            assertThat(call.needs).isEmpty()
            assertThat(text.needs).isEmpty()
        } else {
            assertThat(call.confirm).isEqualTo("Open dialer")
            assertThat(call.needs).containsExactly(Manifest.permission.READ_CONTACTS)
            assertThat(text.confirm).isEqualTo("Open messages")
            assertThat(text.needs).containsExactly(Manifest.permission.READ_CONTACTS)
        }
    }

    @Test
    fun calendar() {
        val agenda = ShowAgenda.prepare(mapOf("day" to "today"), env) as Prepared.Ready
        if (granted(Manifest.permission.READ_CALENDAR)) assertThat(agenda.needs).isEmpty()
        else {
            assertThat(agenda.confirm).isEqualTo("Open calendar")
            assertThat(agenda.needs).containsExactly(Manifest.permission.READ_CALENDAR)
        }
    }

    @Test
    fun notifications() {
        val r = AddReminder.prepare(AddReminder.fromRequest(mapOf("text" to "stretch", "time" to "in 5 minutes"), "remind me in 5 minutes to stretch"), env)
        if (granted(Manifest.permission.POST_NOTIFICATIONS)) assertThat(r).isInstanceOf(Prepared.Ready::class.java)
        else assertThat((r as Prepared.Invalid).needs).containsExactly(Manifest.permission.POST_NOTIFICATIONS)
    }
}

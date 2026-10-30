package dev.abhay.assistant.onboarding

import android.Manifest
import com.google.common.truth.Truth.assertThat
import dev.abhay.assistant.tools.AddReminder
import dev.abhay.assistant.tools.MakeCall
import dev.abhay.assistant.tools.SendText
import dev.abhay.assistant.tools.ToolSettings
import dev.abhay.assistant.tools.Tools
import org.junit.Test

/** Setup's tools page: a row for each group of tools that need setup, and when it's on. */
class SetupGroupsTest {
    @Test fun theGroupsAreExactlyTheToolsThatStartOff() {
        val listed = SetupGroup.entries.flatMap { it.tools }
        assertThat(listed).containsNoDuplicates()
        assertThat(listed).containsExactlyElementsIn(Tools.all.filter { !it.onByDefault })
        assertThat(SetupGroup.entries.map { it.title }).containsExactly("Calls & Messages", "Agenda & Calendar", "Reminders", "Lists").inOrder()
    }

    @Test fun eachGroupAsksForItsToolsPermissionsTogether() {
        assertThat(SetupGroup.CallsAndMessages.permissions).containsExactly(Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE)
        assertThat(SetupGroup.AgendaAndCalendar.permissions).containsExactly(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        assertThat(SetupGroup.Reminders.permissions).containsExactly(Manifest.permission.POST_NOTIFICATIONS)
        // Lists need the notes folder, not a permission.
        assertThat(SetupGroup.Lists.permissions).isEmpty()
        assertThat(SetupGroup.Lists.needsFolder).isTrue()
    }

    @Test fun onlyTheToolsWhosePermissionsWereAllowedCanRun() {
        val contactsOnly = { p: String -> p == Manifest.permission.READ_CONTACTS }
        assertThat(SetupGroup.CallsAndMessages.usable(contactsOnly, folder = false)).containsExactly(SendText)
        assertThat(SetupGroup.CallsAndMessages.usable({ true }, folder = false)).containsExactly(MakeCall, SendText)
        assertThat(SetupGroup.Reminders.usable({ false }, folder = true)).isEmpty()
        assertThat(SetupGroup.Reminders.usable({ true }, folder = false)).containsExactly(AddReminder)
    }

    @Test fun listsNeedAFolderPicked() {
        assertThat(SetupGroup.Lists.usable({ true }, folder = false)).isEmpty()
        assertThat(SetupGroup.Lists.usable({ true }, folder = true)).isEqualTo(SetupGroup.Lists.tools)
    }

    @Test fun aRowIsOnOnceSetUpAndItsUsableToolsAreOn() {
        val defaults = ToolSettings.defaults()
        val on = defaults + SetupGroup.CallsAndMessages.tools.associate { it.name to defaults.getValue(it.name).copy(enabled = true) }
        // Off at first, allowed or not.
        assertThat(SetupGroup.CallsAndMessages.on(defaults, { true }, folder = false)).isFalse()
        assertThat(SetupGroup.CallsAndMessages.on(on, { true }, folder = false)).isTrue()
        // On, but the permissions were taken back since: off.
        assertThat(SetupGroup.CallsAndMessages.on(on, { false }, folder = false)).isFalse()
        // Contacts only: on with Messages alone.
        val messages = defaults + (SendText.name to defaults.getValue(SendText.name).copy(enabled = true))
        assertThat(SetupGroup.CallsAndMessages.on(messages, { it == Manifest.permission.READ_CONTACTS }, folder = false)).isTrue()
    }
}

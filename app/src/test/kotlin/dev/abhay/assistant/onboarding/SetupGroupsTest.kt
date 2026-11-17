package dev.abhay.assistant.onboarding

import android.Manifest
import com.google.common.truth.Truth.assertThat
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

    private val defaults = ToolSettings.defaults()
    private fun on(group: SetupGroup, vararg tools: dev.abhay.assistant.tools.Tool) =
        defaults + (tools.ifEmpty { group.tools.toTypedArray() }).associate { it.name to defaults.getValue(it.name).copy(enabled = true) }
    private val contactsOnly = { p: String -> p == Manifest.permission.READ_CONTACTS }

    @Test fun theSwitchMeansFerbCanDoThisNow() {
        // Review P2-148: on is the tools on with their permissions granted.
        val calls = SetupGroup.CallsAndMessages
        assertThat(calls.state(defaults, { true }, folder = false)).isEqualTo(RowState.Off)
        assertThat(calls.state(on(calls), { true }, folder = false)).isEqualTo(RowState.On)
        // Taken back since: not On (it was, before), but Missing, so the row says so and offers Allow.
        assertThat(calls.state(on(calls), { false }, folder = false)).isEqualTo(RowState.Missing)
        // Contacts allowed, calls refused: not On (it was, before, with "call mum" reaching no tool).
        assertThat(calls.state(on(calls), contactsOnly, folder = false)).isEqualTo(RowState.Missing)
        // Off whatever's granted while none of its tools is on.
        assertThat(SetupGroup.Reminders.state(defaults, { false }, folder = false)).isEqualTo(RowState.Off)
        assertThat(SetupGroup.Reminders.state(on(SetupGroup.Reminders), { true }, folder = false)).isEqualTo(RowState.On)
    }

    @Test fun listsNeedAFolderPicked() {
        val lists = SetupGroup.Lists
        assertThat(lists.state(on(lists), { true }, folder = false)).isEqualTo(RowState.Missing)
        assertThat(lists.state(on(lists), { true }, folder = true)).isEqualTo(RowState.On)
    }
}

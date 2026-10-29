@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package dev.abhay.assistant.ui

import androidx.compose.ui.res.stringResource
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import dev.abhay.assistant.R
import dev.abhay.assistant.reminders.ReminderDraft
import dev.abhay.assistant.reminders.RepeatChoice
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * New reminder, made by hand: M3's full-screen dialog pattern for a short creation form on a phone
 * (https://m3.material.io/components/dialogs/guidelines): Close on the left, Save on the right,
 * the fields below. Opened as a screen, so it moves like the others. Close or Back drops what was
 * typed, without asking. Saved like `add_reminder` without a note ([ReminderDraft.save]); without
 * notifications allowed it asks first, as the tool does.
 */
@Composable
fun NewReminderScreen(viewModel: AssistantViewModel, onDone: () -> Unit) {
    val context = LocalContext.current
    val start = remember { ReminderDraft.new(LocalDateTime.now()) }
    var title by rememberSaveable { mutableStateOf("") }
    var day by rememberSaveable { mutableStateOf(start.date.toEpochDay()) }
    var minute by rememberSaveable { mutableStateOf(start.time.toSecondOfDay() / 60) }
    var repeat by rememberSaveable { mutableStateOf(RepeatChoice.None) }
    var problem by rememberSaveable { mutableStateOf<String?>(null) }
    val draft = ReminderDraft(title, LocalDate.ofEpochDay(day), LocalTime.ofSecondOfDay(minute * 60L), repeat)
    val now = LocalDateTime.now()
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    fun save() {
        saving = true
        scope.launch {
            val saved = withContext(Dispatchers.IO) { viewModel.addReminder(draft) }
            saving = false
            if (saved) onDone() else problem = "That time has passed"
        }
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) save() else problem = NEEDS_NOTIFICATIONS
    }
    val onSave = {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) save()
        else ask.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    var picking by remember { mutableStateOf<Picker?>(null) }
    Scaffold(containerColor = MaterialTheme.colorScheme.surfaceContainer, topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.newreminder_new_reminder)) },
            navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.newreminder_cancel)) } },
            actions = { TextButton(onClick = onSave, enabled = draft.valid(now) && !saving, modifier = Modifier.padding(end = 8.dp)) { Text(stringResource(R.string.newreminder_save)) } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        )
    }) { padding ->
        Column(Modifier.padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { focus.requestFocus() }
            OutlinedTextField(title, { title = it; problem = null }, Modifier.fillMaxWidth().focusRequester(focus), label = { Text(stringResource(R.string.newreminder_title)) },
                singleLine = true, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
            Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                SegmentedListItem(onClick = { picking = Picker.Date }, shapes = ListItemDefaults.segmentedShapes(0, 3),
                    leadingContent = { IconBadge(R.drawable.ic_event) }, supportingContent = { Text(stringResource(R.string.newreminder_date)) }) {
                    Text(draft.date.format(DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.US)))
                }
                SegmentedListItem(onClick = { picking = Picker.Time }, shapes = ListItemDefaults.segmentedShapes(1, 3),
                    leadingContent = { IconBadge(R.drawable.ic_alarm) },
                    supportingContent = { Text((problem ?: draft.problem(now)) ?: "Time", color = if ((problem ?: draft.problem(now)) != null) MaterialTheme.colorScheme.error else androidx.compose.ui.graphics.Color.Unspecified) }) {
                    Text(draft.time.format(DateTimeFormatter.ofPattern("h:mm a", Locale.US)).lowercase())
                }
                Box {
                    SegmentedListItem(onClick = { picking = Picker.Repeat }, shapes = ListItemDefaults.segmentedShapes(2, 3),
                        leadingContent = { IconBadge(R.drawable.ic_reminder) }, supportingContent = { Text(stringResource(R.string.newreminder_repeat)) }) { Text(repeat.label(draft.date)) }
                    DropdownMenu(expanded = picking == Picker.Repeat, onDismissRequest = { picking = null }) {
                        RepeatChoice.entries.forEach { choice ->
                            DropdownMenuItem(text = { Text(choice.label(draft.date)) }, onClick = { repeat = choice; picking = null })
                        }
                    }
                }
            }
        }
    }
    when (picking) {
        Picker.Date -> {
            val today = LocalDate.now()
            val state = rememberDatePickerState(initialSelectedDateMillis = draft.date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
                selectableDates = object : SelectableDates {
                    override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis / 86_400_000L >= today.toEpochDay()
                    override fun isSelectableYear(year: Int) = year >= today.year
                })
            DatePickerDialog(onDismissRequest = { picking = null },
                confirmButton = { TextButton(onClick = { state.selectedDateMillis?.let { day = it / 86_400_000L }; problem = null; picking = null }) { Text(stringResource(R.string.newreminder_ok)) } },
                dismissButton = { TextButton(onClick = { picking = null }) { Text(stringResource(R.string.newreminder_cancel)) } }) { DatePicker(state) }
        }
        Picker.Time -> {
            val state = rememberTimePickerState(initialHour = draft.time.hour, initialMinute = draft.time.minute)
            AlertDialog(onDismissRequest = { picking = null },
                confirmButton = { TextButton(onClick = { minute = state.hour * 60 + state.minute; problem = null; picking = null }) { Text(stringResource(R.string.newreminder_ok)) } },
                dismissButton = { TextButton(onClick = { picking = null }) { Text(stringResource(R.string.newreminder_cancel)) } },
                text = { TimePicker(state) })
        }
        else -> {}
    }
}

private enum class Picker { Date, Time, Repeat }

const val NEEDS_NOTIFICATIONS = "Ferb needs notifications to remind you"

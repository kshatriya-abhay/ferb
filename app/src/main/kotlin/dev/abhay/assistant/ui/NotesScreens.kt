@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package dev.abhay.assistant.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.abhay.assistant.R
import dev.abhay.assistant.reminders.Reminder
import dev.abhay.assistant.reminders.ReminderBook
import dev.abhay.assistant.reminders.ReminderTime
import java.time.LocalDateTime
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.abhay.assistant.notes.NoteRef
import dev.abhay.assistant.notes.NoteStore
import dev.abhay.assistant.notes.SafNotes
import dev.abhay.assistant.notes.VaultCheck
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Notes screen: two primary tabs, swiped between or tapped. Notes: the notes Ferb made in the
 * notes folder ([NoteStore.ferbs]), newest first; tap one to read it. Without a folder, a way to
 * pick one (in the Obsidian vault, say). Reminders: [RemindersTab]. Both are read again whenever the
 * screen comes back, as Obsidian and Syncthing change the folder too.
 */
@Composable
fun NotesScreen(notes: SafNotes, reminders: ReminderBook, onOpen: (NoteRef) -> Unit, onBack: () -> Unit) {
    var resumed by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumed++
        onPauseOrDispose { }
    }
    val list by produceState<List<NoteRef>?>(null, resumed) {
        value = withContext(Dispatchers.IO) { if (notes.ready) NoteStore.ferbs(notes.list(), notes::read).sortedByDescending { it.modified } else null }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) {
            notes.pick(tree)
            resumed++
        }
    }
    // A folder that isn't a vault's top folder: said above the notes, nothing blocked.
    val warning by produceState<String?>(null, resumed) {
        value = withContext(Dispatchers.IO) { if (notes.ready) VaultCheck.warning(notes.vaultCheck()) else null }
    }
    val bar = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val pager = rememberPagerState { NotesTab.entries.size }
    val scope = rememberCoroutineScope()
    val motion = MaterialTheme.motionScheme
    Scaffold(Modifier.nestedScroll(bar.nestedScrollConnection), containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            Column {
                TopBar("Notes", subtitle = notes.folderName(), onBack = onBack, scroll = bar)
                // Primary tabs, under the top bar (https://m3.material.io/components/tabs/guidelines);
                // a tap moves the pager with the theme's spatial spring, as the screens move.
                PrimaryTabRow(selectedTabIndex = pager.currentPage, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    NotesTab.entries.forEach { tab ->
                        Tab(selected = pager.currentPage == tab.ordinal, text = { Text(tab.title) },
                            onClick = { scope.launch { pager.animateScrollToPage(tab.ordinal, animationSpec = motion.defaultSpatialSpec()) } })
                    }
                }
            }
        }) { padding ->
        HorizontalPager(pager, Modifier.padding(padding).fillMaxSize(), verticalAlignment = Alignment.Top) { page ->
            when (NotesTab.entries[page]) {
                NotesTab.Notes -> NotesTab(notes, list, warning, onPick = { pick.launch(null) }, onOpen = onOpen)
                NotesTab.Reminders -> RemindersTab(reminders, resumed, onOpen)
            }
        }
    }
}

enum class NotesTab(val title: String) { Notes("Notes"), Reminders("Reminders") }

@Composable
private fun NotesTab(notes: SafNotes, shown: List<NoteRef>?, warning: String?, onPick: () -> Unit, onOpen: (NoteRef) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        warning?.let {
            Text(it, Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        when {
            !notes.ready -> Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Pick a folder for your notes and lists, such as one in your Obsidian vault. Ferb keeps them there as Markdown files.",
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = onPick) { Text("Pick a notes folder") }
            }
            shown == null -> Loading()
            shown.isEmpty() -> Empty("No notes from Ferb yet. Ask Ferb to take one, or to make a list.")
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = LIST_PADDING, verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                itemsIndexed(shown, key = { _, n -> n.id }) { i, note ->
                    SegmentedListItem(
                        onClick = { onOpen(note) },
                        shapes = ListItemDefaults.segmentedShapes(i, shown.size),
                        supportingContent = { Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(note.modified))) },
                    ) { Text(note.name) }
                }
            }
        }
    }
}

/**
 * Every reminder still to come, soonest first ([ReminderBook.upcoming], what the alarms are set
 * for), with its next time and repeat. Read-only: a row with a note opens it; one without isn't
 * clickable. Read again when the screen comes back and whenever the reminders change (one fires,
 * one is added).
 */
@Composable
private fun RemindersTab(reminders: ReminderBook, resumed: Int, onOpen: (NoteRef) -> Unit) {
    val changed by reminders.changes.collectAsStateWithLifecycle()
    val shown by produceState<Pair<List<Pair<Reminder, NoteRef?>>, LocalDateTime>?>(null, resumed, changed) {
        value = withContext(Dispatchers.IO) { reminders.upcomingWithNotes() to LocalDateTime.now() }
    }
    val (list, now) = shown ?: return Loading()
    if (list.isEmpty()) return Empty(ReminderTime.NONE_UPCOMING)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = LIST_PADDING, verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        itemsIndexed(list, key = { _, (r, _) -> r.id }) { i, (reminder, note) ->
            val shapes = ListItemDefaults.segmentedShapes(i, list.size)
            val supporting = listOfNotNull(ReminderTime.next(reminder.due, now), ReminderTime.repeat(reminder)).joinToString(" · ")
            val leading: @Composable () -> Unit = { IconBadge(R.drawable.ic_reminder) }
            if (note != null) {
                SegmentedListItem(onClick = { onOpen(note) }, shapes = shapes, leadingContent = leading, supportingContent = { Text(supporting) },
                    trailingContent = { Icon(painterResource(R.drawable.ic_note), contentDescription = "Opens its note") }) { Text(reminder.title) }
            } else {
                SegmentedListItem(shapes = shapes, leadingContent = leading, supportingContent = { Text(supporting) }) { Text(reminder.title) }
            }
        }
    }
}

private val LIST_PADDING = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp)

@Composable
private fun Loading() = Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) { LoadingIndicator() }

@Composable
private fun Empty(text: String) = Text(text, Modifier.padding(24.dp), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

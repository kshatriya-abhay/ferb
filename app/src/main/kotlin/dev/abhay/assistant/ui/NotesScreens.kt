@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package dev.abhay.assistant.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.IconButton
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.Tab
import androidx.compose.runtime.rememberCoroutineScope
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
fun NotesScreen(viewModel: AssistantViewModel, onOpen: (NoteRef) -> Unit, onBack: () -> Unit, tab: NotesTab = NotesTab.Notes, onNewReminder: () -> Unit = {}) {
    val notes = viewModel.notes
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
    val pager = rememberPagerState(initialPage = tab.ordinal) { NotesTab.entries.size }
    val scope = rememberCoroutineScope()
    val motion = MaterialTheme.motionScheme
    val snackbar = remember { SnackbarHostState() }
    // Reminders cleared here, hidden at once while the store catches up (and shown again on Undo).
    var hidden by remember { mutableStateOf(emptySet<String>()) }
    val clear = { reminder: Reminder ->
        hidden = hidden + reminder.id
        scope.launch {
            val cleared = withContext(Dispatchers.IO) { viewModel.clearReminder(reminder.id) }
            if (cleared == null) {
                hidden = hidden - reminder.id
                snackbar.showSnackbar("Couldn't clear it")
                return@launch
            }
            val undo = snackbar.showSnackbar("Reminder cleared", actionLabel = "Undo", withDismissAction = true, duration = SnackbarDuration.Long)
            if (undo == SnackbarResult.ActionPerformed) {
                val restored = withContext(Dispatchers.IO) { viewModel.restoreReminder(cleared) }
                hidden = hidden - reminder.id
                if (!restored) snackbar.showSnackbar("Couldn't bring it back: its note has gone")
            }
        }
        Unit
    }
    val reminderList = rememberLazyListState()
    Scaffold(Modifier.nestedScroll(bar.nestedScrollConnection), containerColor = MaterialTheme.colorScheme.surfaceContainer,
        snackbarHost = { SnackbarHost(snackbar) },
        // New reminder: an extended FAB, as its label makes a screen's main action clear
        // (https://m3.material.io/components/extended-fab/guidelines), only on the Reminders tab,
        // scaling and fading with the theme's springs as the tab changes, and collapsing to its
        // icon once the list scrolls, as the guidance suggests for long lists.
        floatingActionButton = {
            AnimatedVisibility(pager.targetPage == NotesTab.Reminders.ordinal,
                enter = scaleIn(motion.defaultSpatialSpec()) + fadeIn(motion.defaultEffectsSpec()),
                exit = scaleOut(motion.defaultSpatialSpec()) + fadeOut(motion.defaultEffectsSpec())) {
                val atTop by remember { derivedStateOf { reminderList.firstVisibleItemIndex == 0 && reminderList.firstVisibleItemScrollOffset == 0 } }
                ExtendedFloatingActionButton(onClick = onNewReminder, expanded = atTop,
                    icon = { Icon(painterResource(R.drawable.ic_alarm_add), contentDescription = null) }, text = { Text("New reminder") })
            }
        },
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
                NotesTab.Reminders -> RemindersTab(viewModel, reminderList, resumed, hidden, onOpen, onClear = clear)
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
 * for), with its next time and repeat. A row with a note opens it; one without isn't clickable.
 * Each has a clear button ([onClear]); those just cleared are [hidden]. Read again when the screen
 * comes back and whenever the reminders change (one fires, is added or cleared).
 */
@Composable
private fun RemindersTab(viewModel: AssistantViewModel, state: LazyListState, resumed: Int, hidden: Set<String>, onOpen: (NoteRef) -> Unit, onClear: (Reminder) -> Unit) {
    val reminders = viewModel.reminders
    val changed by reminders.changes.collectAsStateWithLifecycle()
    val shown by produceState<Pair<List<Pair<Reminder, NoteRef?>>, LocalDateTime>?>(null, resumed, changed) {
        value = withContext(Dispatchers.IO) { reminders.upcomingWithNotes() to LocalDateTime.now() }
    }
    val (all, now) = shown ?: return Loading()
    val list = all.filter { (r, _) -> r.id !in hidden }
    if (list.isEmpty()) return Empty(ReminderTime.NONE_UPCOMING)
    // One just made by hand: scrolled to, in its place.
    val added by viewModel.justAdded.collectAsStateWithLifecycle()
    LaunchedEffect(list, added) {
        val at = list.indexOfFirst { (r, _) -> r.id == added }
        if (at >= 0) {
            state.animateScrollToItem(at)
            viewModel.justAdded.value = null
        }
    }
    // Room under the last row for the FAB.
    LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        itemsIndexed(list, key = { _, (r, _) -> r.id }) { i, (reminder, note) ->
            val shapes = ListItemDefaults.segmentedShapes(i, list.size)
            val supporting = listOfNotNull(ReminderTime.next(reminder.due, now), ReminderTime.repeat(reminder)).joinToString(" · ")
            // A note's icon on those that open one.
            val leading: @Composable () -> Unit = { IconBadge(if (note != null) R.drawable.ic_note else R.drawable.ic_reminder) }
            val trailing: @Composable () -> Unit = {
                IconButton(onClick = { onClear(reminder) }) { Icon(Icons.Default.Close, contentDescription = "Clear reminder") }
            }
            val row = Modifier.animateItem()
            if (note != null) {
                SegmentedListItem(onClick = { onOpen(note) }, shapes = shapes, modifier = row, leadingContent = leading,
                    supportingContent = { Text(supporting) }, trailingContent = trailing) { Text(reminder.title) }
            } else {
                SegmentedListItem(shapes = shapes, modifier = row, leadingContent = leading, supportingContent = { Text(supporting) },
                    trailingContent = trailing) { Text(reminder.title) }
            }
        }
    }
}

private val LIST_PADDING = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp)

@Composable
private fun Loading() = Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) { LoadingIndicator() }

@Composable
private fun Empty(text: String) = Text(text, Modifier.padding(24.dp), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

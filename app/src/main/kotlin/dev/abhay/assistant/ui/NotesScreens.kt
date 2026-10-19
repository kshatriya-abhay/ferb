@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package dev.abhay.assistant.ui

import androidx.activity.compose.rememberLauncherForActivityResult
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
 * The notes Ferb made in the notes folder ([NoteStore.ferbs]), newest first; tap one to read it. Without a folder, a way to pick one
 * (in the Obsidian vault, say). Read again whenever the screen comes back, as Obsidian and
 * Syncthing change the folder too.
 */
@Composable
fun NotesScreen(notes: SafNotes, onOpen: (NoteRef) -> Unit, onBack: () -> Unit) {
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
    Scaffold(Modifier.nestedScroll(bar.nestedScrollConnection), containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = { TopBar("Notes", subtitle = notes.folderName(), onBack = onBack, scroll = bar) }) { padding ->
        val shown = list
        Column(Modifier.padding(padding)) {
        warning?.let {
            Text(it, Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        when {
            !notes.ready -> Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Pick a folder for your notes and lists, such as one in your Obsidian vault. Ferb keeps them there as Markdown files.",
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { pick.launch(null) }) { Text("Pick a notes folder") }
            }
            shown == null -> Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) { LoadingIndicator() }
            shown.isEmpty() -> Text("No notes from Ferb yet. Ask Ferb to take one, or to make a list.", Modifier.padding(24.dp),
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
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
}

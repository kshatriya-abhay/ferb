@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package dev.abhay.assistant.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.m3.Markdown
import dev.abhay.assistant.notes.NoteRef
import dev.abhay.assistant.notes.NoteText
import dev.abhay.assistant.notes.SafNotes
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The notes folder's notes, newest first; tap one to read it. Without a folder, a way to pick one
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
        value = withContext(Dispatchers.IO) { if (notes.ready) notes.list().sortedByDescending { it.modified } else null }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) {
            notes.pick(tree)
            resumed++
        }
    }
    val bar = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    BackHandler(onBack = onBack)
    Scaffold(Modifier.nestedScroll(bar.nestedScrollConnection), containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = { TopBar("Notes", subtitle = notes.folderName(), onBack = onBack, scroll = bar) }) { padding ->
        val shown = list
        when {
            !notes.ready -> Column(Modifier.padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Pick a folder for your notes and lists, such as one in your Obsidian vault. Ferb keeps them there as Markdown files.",
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { pick.launch(null) }) { Text("Pick a notes folder") }
            }
            shown == null -> Column(Modifier.padding(padding).fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) { LoadingIndicator() }
            shown.isEmpty() -> Text("No notes yet. Ask Ferb to take one, or to add to a list.", Modifier.padding(padding).padding(24.dp),
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
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

/**
 * One note, as Markdown (mikepenz's renderer): its frontmatter folded into "Properties", its tasks
 * as boxes that tick in the file (one character, [NoteText.flip]). Edit opens the editor.
 */
@Composable
fun NoteScreen(notes: SafNotes, note: NoteRef, onEdit: () -> Unit, onBack: () -> Unit) {
    var version by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        version++
        onPauseOrDispose { }
    }
    val text by produceState<String?>(null, version) { value = withContext(Dispatchers.IO) { notes.read(note) } }
    val scope = rememberCoroutineScope()
    var showProperties by rememberSaveable { mutableStateOf(false) }
    BackHandler(onBack = onBack)
    val bar = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(Modifier.nestedScroll(bar.nestedScrollConnection),
        topBar = {
            androidx.compose.material3.LargeFlexibleTopAppBar(
                title = { Text(note.name) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = { IconButton(onClick = onEdit, enabled = text != null) { Icon(Icons.Default.Edit, contentDescription = "Edit") } },
                scrollBehavior = bar,
            )
        }) { padding ->
        val current = text
        if (current == null) {
            Column(Modifier.padding(padding).fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) { LoadingIndicator() }
            return@Scaffold
        }
        val head = NoteText.head(current)
        val body = NoteText.body(current)
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
            if (head.isNotEmpty()) {
                TextButton(onClick = { showProperties = !showProperties }) {
                    Text("Properties")
                    Icon(if (showProperties) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = null)
                }
                if (showProperties) Text(head.trim().removePrefix("---").removeSuffix("---").trim(), Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Markdown(
                content = body,
                modifier = Modifier.fillMaxWidth(),
                components = markdownComponents(checkbox = { model ->
                    val at = model.node.startOffset
                    val checked = model.content.getOrNull(at + 1)?.let { it == 'x' || it == 'X' } == true
                    Checkbox(checked = checked, onCheckedChange = {
                        scope.launch {
                            withContext(Dispatchers.IO) { notes.update(note) { now -> toggled(current, now, head.length + at + 1) } }
                            version++
                        }
                    })
                }),
            )
        }
    }
}

/**
 * [now] (the file as it is) with the task whose mark was at [mark] in [shown] (the text on screen)
 * flipped: at that offset if the file hasn't changed, else the same task (same place in order,
 * same text) if it's still there; else unchanged.
 */
internal fun toggled(shown: String, now: String, mark: Int): String {
    if (now == shown) return NoteText.flip(now, mark) ?: now
    val index = NoteText.tasks(shown).indexOfFirst { it.mark == mark }
    val was = NoteText.tasks(shown).getOrNull(index) ?: return now
    val same = NoteText.tasks(now).getOrNull(index)?.takeIf { it.text == was.text && it.checked == was.checked } ?: return now
    return NoteText.flip(now, same.mark) ?: now
}

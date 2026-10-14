@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package dev.abhay.assistant.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.abhay.assistant.notes.NoteEdit
import dev.abhay.assistant.notes.NoteRef
import dev.abhay.assistant.notes.NoteText
import dev.abhay.assistant.notes.SafNotes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A note as raw Markdown, styled as it's typed ([NoteEdit.styles]: headings, bold, links, tasks…)
 * without changing a character, and saved exactly as written ([NoteEdit.save]): the frontmatter is
 * kept apart, shown folded, and joined back as it is; the file's line endings stay. If the note
 * changed elsewhere meanwhile, Ferb asks before overwriting it.
 */
@Composable
fun NoteEditorScreen(notes: SafNotes, note: NoteRef, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var opened by remember { mutableStateOf<String?>(null) }
    var value by remember { mutableStateOf(TextFieldValue("")) }
    var leaving by remember { mutableStateOf(false) }
    var conflict by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(note.id) {
        val text = withContext(Dispatchers.IO) { notes.read(note) }
        opened = text
        value = TextFieldValue(text?.let(NoteEdit::editable).orEmpty())
    }
    val original = opened
    val changed = original != null && value.text != NoteEdit.editable(original)
    fun write(text: String) = scope.launch {
        val ok = withContext(Dispatchers.IO) { notes.update(note) { text } }
        if (ok) onDone() else failed = true
    }
    fun save() {
        val start = opened ?: return
        scope.launch {
            val now = withContext(Dispatchers.IO) { notes.read(note) } ?: start
            when (val decision = NoteEdit.save(start, now, value.text)) {
                NoteEdit.Save.Nothing -> onDone()
                is NoteEdit.Save.Write -> write(decision.text)
                is NoteEdit.Save.Conflict -> conflict = decision.mine
            }
        }
    }
    BackHandler { if (changed) leaving = true else onDone() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(note.name) },
                navigationIcon = { IconButton(onClick = { if (changed) leaving = true else onDone() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = { IconButton(onClick = ::save, enabled = changed) { Icon(Icons.Default.Check, contentDescription = "Save") } },
            )
        },
    ) { padding ->
        if (original == null) {
            Column(Modifier.padding(padding).fillMaxWidth().padding(32.dp)) { LoadingIndicator() }
            return@Scaffold
        }
        val head = NoteText.head(original)
        val colors = MaterialTheme.colorScheme
        val styling = remember(colors) { MarkdownStyling(colors.primary, colors.onSurfaceVariant, colors.surfaceContainerHigh) }
        Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
            if (head.isNotEmpty()) {
                Text("Properties: " + head.lineSequence().drop(1).filter { it.isNotBlank() && it.trimEnd('\r') != "---" }.joinToString(", ") { it.substringBefore(':').trim() },
                    Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            }
            BasicTextField(
                value = value,
                onValueChange = { value = it },
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                cursorBrush = SolidColor(colors.primary),
                visualTransformation = styling,
            )
        }
    }

    if (leaving) AlertDialog(
        onDismissRequest = { leaving = false },
        title = { Text("Save changes?") },
        confirmButton = { TextButton(onClick = { leaving = false; save() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = { leaving = false; onDone() }) { Text("Discard") } },
    )
    conflict?.let { mine ->
        AlertDialog(
            onDismissRequest = { conflict = null },
            title = { Text("Changed elsewhere") },
            text = { Text("This note changed since you opened it, in Obsidian or by sync. Keep your version, or drop it and keep theirs?") },
            confirmButton = { TextButton(onClick = { conflict = null; write(mine) }) { Text("Keep mine") } },
            dismissButton = { TextButton(onClick = { conflict = null; onDone() }) { Text("Keep theirs") } },
        )
    }
    if (failed) AlertDialog(
        onDismissRequest = { failed = false },
        title = { Text("Couldn't save") },
        text = { Text("The notes folder couldn't be written. Your text is still here.") },
        confirmButton = { TextButton(onClick = { failed = false }) { Text("OK") } },
    )
}

/** Styles the raw Markdown in place: the same characters, so offsets map one to one. */
private class MarkdownStyling(private val accent: androidx.compose.ui.graphics.Color, private val muted: androidx.compose.ui.graphics.Color,
                              private val codeBackground: androidx.compose.ui.graphics.Color) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val styled = AnnotatedString.Builder(text.text)
        NoteEdit.styles(text.text).forEach { span ->
            val style = when (span.kind) {
                NoteEdit.Kind.Heading -> SpanStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp, color = accent)
                NoteEdit.Kind.Bold -> SpanStyle(fontWeight = FontWeight.Bold)
                NoteEdit.Kind.Italic -> SpanStyle(fontStyle = FontStyle.Italic)
                NoteEdit.Kind.Code -> SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)
                NoteEdit.Kind.Link -> SpanStyle(color = accent, textDecoration = TextDecoration.Underline)
                NoteEdit.Kind.Task -> SpanStyle(color = accent, fontFamily = FontFamily.Monospace)
                NoteEdit.Kind.Done -> SpanStyle(color = muted, fontFamily = FontFamily.Monospace)
                NoteEdit.Kind.Marker -> SpanStyle(color = accent)
                NoteEdit.Kind.Quote -> SpanStyle(color = muted, fontStyle = FontStyle.Italic)
            }
            styled.addStyle(style, span.start, span.end)
        }
        return TransformedText(styled.toAnnotatedString(), OffsetMapping.Identity)
    }
}

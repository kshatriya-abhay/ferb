package dev.abhay.assistant.notes

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import java.util.UUID

/** A note file in the notes folder: [id] Ferb's handle on it (its document), [name] without ".md". */
data class NoteRef(val id: String, val name: String, val modified: Long)

/**
 * The user's notes folder (inside their Obsidian vault, synced by Syncthing): `.md` files Ferb
 * lists, reads, creates and changes in small ways ([NoteText]). [update] always re-reads the file
 * just before writing, so a change made meanwhile in Obsidian or by Syncthing is kept. Syncthing's
 * conflict copies are left alone.
 */
interface NoteStore {
    /** Whether a folder is picked and still readable. */
    val ready: Boolean

    fun list(): List<NoteRef>
    fun read(note: NoteRef): String?

    /** Applies [change] to the file as it is now and saves it; false if it couldn't. */
    fun update(note: NoteRef, change: (String) -> String): Boolean

    /** A new note `<name>.md` (or "<name> 2.md" if taken) with [text]; null if it couldn't. */
    fun create(name: String, text: String): NoteRef?
    fun delete(note: NoteRef): Boolean

    companion object {
        /** A permission-like need: a request that needs the notes folder asks for it (see [dev.abhay.assistant.tools.Prepared]). */
        const val NEEDS_FOLDER = "ferb.notes-folder"

        /** A file name for [title]: what Obsidian allows, no more than 80 characters, "Note" when nothing's left. */
        fun fileName(title: String): String =
            title.replace(Regex("""[\\/:*?"<>|#^\[\]]"""), "").replace(Regex("""\s+"""), " ").trim().take(80).trim().ifEmpty { "Note" }

        /** The best note named like [query] (case aside, then by spelling or sound, as contacts are), or null. */
        fun find(query: String, notes: List<NoteRef>): NoteRef? {
            val q = query.trim().lowercase().removeSuffix(" list").removePrefix("my ").removePrefix("the ").trim()
            if (q.isEmpty()) return null
            notes.firstOrNull { it.name.lowercase() == q || it.name.lowercase() == "$q list" }?.let { return it }
            return notes.map { it to dev.abhay.assistant.tools.ContactMatch.word(q.replace(" ", ""), it.name.lowercase().removeSuffix(" list").replace(" ", "")) }
                .filter { it.second >= 0.8 }.maxByOrNull { it.second }?.first
        }

        /**
         * The notes Ferb made, for the Notes screen: those with Ferb's id in their frontmatter
         * (the notes and lists it created). The folder's other notes
         * aren't listed, though the tools still find them by name (a list kept by hand).
         */
        fun ferbs(notes: List<NoteRef>, read: (NoteRef) -> String?): List<NoteRef> =
            notes.filter { note -> read(note)?.let { NoteText.get(it, NoteText.ID) }.isNullOrBlank().not() }

        /** A new note's id for its frontmatter. */
        fun newId(): String = UUID.randomUUID().toString()

        fun isNote(name: String) = name.endsWith(".md", ignoreCase = true) && !name.contains(".sync-conflict-")
    }
}

/**
 * The folder the user picked through the Storage Access Framework (`ACTION_OPEN_DOCUMENT_TREE`),
 * kept with a persistable permission; its `.md` files, not its subfolders. No permission beyond
 * the user's pick, and no network.
 */
class SafNotes(
    private val context: Context,
    /** Whether Ferb may use [folder]: the user's pick, kept as a persistable grant. Device tests use their own folder. */
    private val granted: (Uri) -> Boolean = { tree -> context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission } },
    /** Where the folder is remembered; device tests use their own, so the user's pick is untouched. */
    prefsName: String = "notes",
) : NoteStore {
    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    /** The picked folder, or null. */
    var folder: Uri?
        get() = prefs.getString(FOLDER, null)?.let(Uri::parse)
        set(value) {
            prefs.edit().putString(FOLDER, value?.toString()).apply()
        }

    /** Keeps access to [tree] across restarts and makes it the notes folder. */
    fun pick(tree: Uri) {
        context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        folder = tree
    }

    /**
     * Whether the folder is an Obsidian vault's top folder ([VaultCheck]): `.obsidian` looked up
     * by that exact name, not by listing. Call off the main thread.
     */
    fun vaultCheck(): VaultCheck.Outcome {
        val tree = folder ?: return VaultCheck.Outcome.Unknown
        val id = VaultCheck.entryId(tree.authority.orEmpty(), DocumentsContract.getTreeDocumentId(tree)) ?: return VaultCheck.Outcome.Unknown
        val exists = try {
            context.contentResolver.query(DocumentsContract.buildDocumentUriUsingTree(tree, id),
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { it.moveToFirst() } ?: false
        } catch (e: java.io.FileNotFoundException) {
            false
        } catch (e: SecurityException) {
            null
        } catch (e: IllegalArgumentException) {
            // Some providers say "not a descendant" for an id that doesn't exist.
            false
        }
        return VaultCheck.outcome(exists)
    }

    /** The folder's name, for Settings. */
    fun folderName(): String? = folder?.let { DocumentsContract.getTreeDocumentId(it).substringAfterLast(':').substringAfterLast('/').ifEmpty { "Notes" } }

    override val ready: Boolean
        get() = folder?.let(granted) == true

    override fun list(): List<NoteRef> {
        val tree = folder ?: return emptyList()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_MIME_TYPE)
        return try {
            context.contentResolver.query(children, columns, null, null, null)?.use { c ->
                buildList {
                    while (c.moveToNext()) {
                        val name = c.getString(1) ?: continue
                        if (c.getString(3) == DocumentsContract.Document.MIME_TYPE_DIR || !NoteStore.isNote(name)) continue
                        add(NoteRef(DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)).toString(), name.removeSuffix(".md").removeSuffix(".MD"), c.getLong(2)))
                    }
                }
            }.orEmpty()
        } catch (e: Exception) {
            emptyList()
        }
    }

    override fun read(note: NoteRef): String? = try {
        context.contentResolver.openInputStream(Uri.parse(note.id))?.use { it.readBytes().toString(Charsets.UTF_8) }
    } catch (e: Exception) {
        null
    }

    override fun update(note: NoteRef, change: (String) -> String): Boolean {
        // Read now, not earlier: Syncthing or Obsidian may have changed the file since Ferb last read it.
        val now = read(note) ?: return false
        val next = change(now)
        if (next == now) return true
        return write(Uri.parse(note.id), next)
    }

    override fun create(name: String, text: String): NoteRef? {
        val tree = folder ?: return null
        val taken = list().map { it.name.lowercase() }.toSet()
        val base = NoteStore.fileName(name)
        val free = generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base $it" }.first { it.lowercase() !in taken }
        return try {
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val uri = DocumentsContract.createDocument(context.contentResolver, parent, "text/markdown", "$free.md") ?: return null
            if (!write(uri, text)) return null
            NoteRef(uri.toString(), free, System.currentTimeMillis())
        } catch (e: Exception) {
            null
        }
    }

    override fun delete(note: NoteRef): Boolean = try {
        DocumentsContract.deleteDocument(context.contentResolver, Uri.parse(note.id))
    } catch (e: Exception) {
        false
    }

    private fun write(uri: Uri, text: String): Boolean = try {
        // "wt": truncate, so a shorter text leaves no tail of the old one.
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) } != null
    } catch (e: Exception) {
        false
    }

    private companion object {
        const val FOLDER = "folder"
    }
}

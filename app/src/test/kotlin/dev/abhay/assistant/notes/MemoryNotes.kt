package dev.abhay.assistant.notes

/** A notes folder in memory, for tests: name → text. */
class MemoryNotes(vararg notes: Pair<String, String>, override val ready: Boolean = true) : NoteStore {
    val files = linkedMapOf(*notes)
    private var clock = 1L

    override fun list() = files.keys.map { NoteRef(it, it, clock) }
    override fun read(note: NoteRef) = files[note.id]
    override fun update(note: NoteRef, change: (String) -> String): Boolean {
        val now = files[note.id] ?: return false
        files[note.id] = change(now)
        return true
    }
    override fun create(name: String, text: String): NoteRef? {
        val base = NoteStore.fileName(name)
        val free = generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base $it" }.first { it !in files }
        files[free] = text
        return NoteRef(free, free, clock++)
    }
    override fun delete(note: NoteRef) = files.remove(note.id) != null
}

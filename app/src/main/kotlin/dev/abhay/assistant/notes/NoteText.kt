package dev.abhay.assistant.notes

/**
 * Small, exact edits to a note's Markdown, as Ferb makes them in files the user also edits in
 * Obsidian (synced by Syncthing): a frontmatter key set or removed, lines appended, one checkbox
 * ticked. Everything else stays byte for byte: other frontmatter lines, the body, line endings
 * (LF or CRLF, as the file has them), and a missing final newline.
 */
object NoteText {
    /** Ferb's frontmatter keys: the note's id (kept across renames), a reminder time, its repeat. */
    const val ID = "ferb-id"
    const val REMIND = "remind"
    const val REPEAT = "repeat"

    /** The file's line ending: CRLF when its first line ends so, else LF. */
    fun newline(text: String) = if (text.indexOf('\n').let { it > 0 && text[it - 1] == '\r' }) "\r\n" else "\n"

    /** Where the frontmatter is: its whole block (from `---` to the closing line, ending included) and its inside. */
    data class Block(val start: Int, val end: Int, val innerStart: Int, val innerEnd: Int)

    /** The YAML frontmatter at the very start (`---` lines around it), or null. */
    fun frontmatter(text: String): Block? {
        val first = lineEnd(text, 0)
        if (text.substring(0, first.first).trimEnd('\r') != "---") return null
        var at = first.second
        while (at < text.length) {
            val (end, next) = lineEnd(text, at)
            val line = text.substring(at, end).trimEnd('\r')
            if (line == "---" || line == "...") return Block(0, next, first.second, at)
            at = next
        }
        return null
    }

    /** The note without its frontmatter: what the viewer shows and the editor edits. */
    fun body(text: String): String = frontmatter(text)?.let { text.substring(it.end) } ?: text

    /** The frontmatter block as it is, empty without one: kept apart while the body is edited. */
    fun head(text: String): String = frontmatter(text)?.let { text.substring(0, it.end) } ?: ""

    /** A simple `key: value` from the frontmatter (quotes trimmed), or null. */
    fun get(text: String, key: String): String? {
        val block = frontmatter(text) ?: return null
        return keyLines(text, block).firstOrNull { it.key == key }?.value
    }

    /**
     * [text] with frontmatter [key] set to [value] (or removed, when null): that line replaced in
     * place, or added before the closing `---`, or a new frontmatter block at the top. Nothing else
     * changes.
     */
    fun set(text: String, key: String, value: String?): String {
        val nl = newline(text)
        val block = frontmatter(text)
        if (block == null) return if (value == null) text else "---$nl$key: $value$nl---$nl$text"
        val line = keyLines(text, block).firstOrNull { it.key == key }
        return when {
            line != null && value == null -> text.removeRange(line.start, line.next)
            line != null -> text.replaceRange(line.start, line.end, "$key: $value")
            value == null -> text
            else -> text.substring(0, block.innerEnd) + "$key: $value$nl" + text.substring(block.innerEnd)
        }
    }

    /** [text] with [lines] added at the end, each on its own line, in the file's line ending. */
    fun append(text: String, lines: List<String>): String {
        if (lines.isEmpty()) return text
        val nl = newline(text)
        val sep = if (text.isEmpty() || text.endsWith("\n")) "" else nl
        return text + sep + lines.joinToString("") { it + nl }
    }

    /** A Markdown task: its text, whether it's ticked, and where its mark (the space or x) is. */
    data class Task(val text: String, val checked: Boolean, val mark: Int, val line: Int)

    private val TASK = Regex("""^[ \t]*[-*+][ \t]+\[([ xX])\][ \t]+(.*)$""")

    /** The body's tasks (`- [ ] milk`, `- [x] eggs`), in order, with offsets into the whole [text]. */
    fun tasks(text: String): List<Task> {
        val from = frontmatter(text)?.end ?: 0
        val out = mutableListOf<Task>()
        var at = from
        var n = 0
        while (at < text.length) {
            val (end, next) = lineEnd(text, at)
            val line = text.substring(at, end).trimEnd('\r')
            TASK.find(line)?.let { m -> out += Task(m.groupValues[2].trim(), m.groupValues[1] != " ", at + m.groups[1]!!.range.first, n) }
            at = next
            n++
        }
        return out
    }

    /** [text] with the task whose mark is at [mark] ticked or unticked: one character, `[ ]` ↔ `[x]`. Null if there's no task mark there. */
    fun flip(text: String, mark: Int): String? {
        if (mark !in 1 until text.length - 1 || text[mark - 1] != '[' || text[mark + 1] != ']') return null
        val next = when (text[mark]) {
            ' ' -> 'x'
            'x', 'X' -> ' '
            else -> return null
        }
        return text.substring(0, mark) + next + text.substring(mark + 1)
    }

    private data class KeyLine(val key: String, val value: String, val start: Int, val end: Int, val next: Int)

    private fun keyLines(text: String, block: Block): List<KeyLine> {
        val out = mutableListOf<KeyLine>()
        var at = block.innerStart
        while (at < block.innerEnd) {
            val (end, next) = lineEnd(text, at)
            val raw = text.substring(at, end)
            val line = raw.trimEnd('\r')
            val colon = line.indexOf(':')
            if (colon > 0 && !line[0].isWhitespace()) {
                out += KeyLine(line.substring(0, colon).trim(), line.substring(colon + 1).trim().trim('"', '\''), at, at + line.length, next)
            }
            at = next
        }
        return out
    }

    /** The end of the line starting at [at] (before its `\n`) and where the next line starts. */
    private fun lineEnd(text: String, at: Int): Pair<Int, Int> {
        val nl = text.indexOf('\n', at)
        return if (nl < 0) text.length to text.length else nl to nl + 1
    }
}

package dev.abhay.assistant.notes

/**
 * Editing a note as raw Markdown, saved exactly as written: the body is edited with LF line
 * endings and written back in the file's own (CRLF stays CRLF), the frontmatter is kept apart and
 * joined back as it is, and nothing is written when nothing changed. Light styling marks the
 * Markdown as typed ([styles]) without changing a character.
 */
object NoteEdit {
    /** What's edited: the body, with LF line endings. */
    fun editable(file: String): String = NoteText.body(file).replace("\r\n", "\n")

    /** The file to write for [edited] text, given the file as it was opened: same frontmatter and line endings. */
    fun file(opened: String, edited: String): String {
        val body = if (NoteText.newline(opened) == "\r\n") edited.replace("\r\n", "\n").replace("\n", "\r\n") else edited
        return NoteText.head(opened) + body
    }

    /** What saving does, given the file as opened, as it is [now], and the [edited] body. */
    sealed interface Save {
        /** Nothing changed: nothing to write. */
        data object Nothing : Save
        /** Write [text]. */
        data class Write(val text: String) : Save
        /** The body changed elsewhere too (Obsidian, Syncthing): the user chooses. [mine] keeps this edit over it. */
        data class Conflict(val mine: String) : Save
    }

    fun save(opened: String, now: String, edited: String): Save {
        if (edited == editable(opened)) return Save.Nothing
        return when {
            now == opened -> Save.Write(file(opened, edited))
            // Only the frontmatter changed meanwhile (a reminder moved, say): keep it, with this body.
            NoteText.body(now) == NoteText.body(opened) -> Save.Write(file(now, edited))
            else -> Save.Conflict(file(now, edited))
        }
    }

    /** A styled stretch of the body: [start] until [end], as [kind]. */
    data class Span(val start: Int, val end: Int, val kind: Kind)

    enum class Kind { Heading, Bold, Italic, Code, Link, Task, Done, Marker, Quote }

    private val HEADING = Regex("""(?m)^#{1,6}[ \t].*$""")
    private val BOLD = Regex("""(\*\*|__)(?=\S)(.+?)(?<=\S)\1""")
    private val ITALIC = Regex("""(?<![*\w])([*_])(?=\S)([^*_\n]+?)(?<=\S)\1(?![*\w])""")
    private val CODE = Regex("""`[^`\n]+`""")
    private val LINK = Regex("""\[\[[^\]\n]+]]|\[[^\]\n]+]\([^)\n]+\)|https?://\S+""")
    private val TASK = Regex("""(?m)^[ \t]*[-*+][ \t]+\[([ xX])]""")
    private val MARKER = Regex("""(?m)^[ \t]*(?:[-*+]|\d+[.)])(?=[ \t])""")
    private val QUOTE = Regex("""(?m)^>.*$""")

    /** The body's Markdown to style as it's typed: headings, bold, italic, code, links (and Obsidian's [[links]]), tasks, list markers, quotes. */
    fun styles(text: String): List<Span> = buildList {
        HEADING.findAll(text).forEach { add(Span(it.range.first, it.range.last + 1, Kind.Heading)) }
        QUOTE.findAll(text).forEach { add(Span(it.range.first, it.range.last + 1, Kind.Quote)) }
        BOLD.findAll(text).forEach { add(Span(it.range.first, it.range.last + 1, Kind.Bold)) }
        ITALIC.findAll(text).forEach { add(Span(it.range.first, it.range.last + 1, Kind.Italic)) }
        CODE.findAll(text).forEach { add(Span(it.range.first, it.range.last + 1, Kind.Code)) }
        LINK.findAll(text).forEach { add(Span(it.range.first, it.range.last + 1, Kind.Link)) }
        val tasks = TASK.findAll(text).toList()
        tasks.forEach { add(Span(it.range.first, it.range.last + 1, if (it.groupValues[1] == " ") Kind.Task else Kind.Done)) }
        val taskStarts = tasks.map { it.range.first }.toSet()
        MARKER.findAll(text).filter { it.range.first !in taskStarts }.forEach { add(Span(it.range.first, it.range.last + 1, Kind.Marker)) }
    }
}

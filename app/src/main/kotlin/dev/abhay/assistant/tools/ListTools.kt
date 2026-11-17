package dev.abhay.assistant.tools

import dev.abhay.assistant.engine.FerbTime
import dev.abhay.assistant.R
import dev.abhay.assistant.notes.NoteRef
import dev.abhay.assistant.notes.NoteStore
import dev.abhay.assistant.notes.NoteText

/**
 * Lists are notes in the notes folder with Markdown tasks (`- [ ] milk`), as Obsidian shows them.
 * Ferb only appends tasks and ticks boxes: a file synced by Syncthing is never rewritten whole.
 * Without a folder picked, the reply asks for one ([NoteStore.NEEDS_FOLDER]).
 */
internal object Lists {
    const val NO_FOLDER = "Lists are notes in your notes folder: pick one, in your Obsidian vault for example"

    /**
     * The items a request names: "milk, eggs and bread" → milk, eggs, bread. Items are kept as said
     * (the content fix), so Hindi's "and" separates them too: "chawal aur dal", "चावल और दाल".
     */
    fun items(text: String): List<String> =
        // "ek aur doodh" is one more milk, one item: "aur" after a count doesn't separate (review P2-76).
        text.replace(ONE_MORE) { it.value.replace(Regex("""aur|और"""), KEEP) }
            .split(Regex(""",|;|\b(?:and|aur)\b|(?<![\p{L}\p{M}])और(?![\p{L}\p{M}])|\n"""))
            .map { it.replace(KEEP, "aur").trim().trim('.', '"').trim() }.filter { it.isNotEmpty() }

    private val ONE_MORE = Regex("""(?i)(?:\b(?:ek|\d+)|(?<![\p{L}\p{M}])एक)\s+(?:aur\b|और(?![\p{L}\p{M}]))""")
    private const val KEEP = "\uE000"

    /** A list's name as a note's: "the shopping list" → "Shopping". */
    fun title(list: String): String = NoteStore.listName(list).replaceFirstChar { it.uppercase() }

    fun needsFolder() = Prepared.Invalid(NO_FOLDER, needs = listOf(NoteStore.NEEDS_FOLDER))

    /** The list's unticked tasks, each tickable from the chat; the result refreshes after each tick. */
    fun shown(notes: NoteStore, note: NoteRef, open: (android.content.Context, NoteRef) -> Unit = { _, _ -> }): Outcome {
        val text = notes.read(note) ?: return Outcome.Failed("Couldn't read “${note.name}”")
        val unticked = NoteText.tasks(text).filter { !it.checked }
        val message = when (unticked.size) {
            0 -> "${note.name}: nothing left"
            1 -> "${note.name}: 1 item"
            else -> "${note.name}: ${unticked.size} items"
        }
        return Outcome.Done(message, actions = listOf(FollowUp("Open") { env -> open(env.context, note); shown(notes, note, open) }), lines = unticked.map { task ->
            Line("", task.text, tick = FollowUp("Tick ${task.text}") { _ ->
                // Ticks that task as the file is now: the first unticked one with the same text.
                val ticked = notes.update(note) { now ->
                    NoteText.tasks(now).firstOrNull { !it.checked && it.text == task.text }?.let { NoteText.flip(now, it.mark) } ?: now
                }
                if (ticked) shown(notes, note, open) else Outcome.Failed("Couldn't change “${note.name}”")
            })
        })
    }

    /** The unticked task in [text] that best matches [item] (as said or by sound), or null. */
    fun match(text: String, item: String): NoteText.Task? = best(text, item)?.first

    /**
     * [match] and how well: 3 the same, 2 all the words of one in the other ("milk" in "oat milk"),
     * 1 by sound. Blank tasks never match, and words are whole: "tea" isn't in "steak" (review P2-73).
     */
    fun best(text: String, item: String): Pair<NoteText.Task, Int>? {
        val open = NoteText.tasks(text).filter { !it.checked && it.text.isNotBlank() }
        val q = item.trim().lowercase()
        if (q.isEmpty()) return null
        fun words(s: String) = s.lowercase().split(Regex("""\s+""")).filter { it.isNotEmpty() }.toSet()
        val said = words(q)
        open.firstOrNull { it.text.trim().lowercase() == q }?.let { return it to 3 }
        open.firstOrNull { words(it.text).let { w -> w.containsAll(said) || said.containsAll(w) } }?.let { return it to 2 }
        return open.map { it to ContactMatch.word(q.replace(" ", ""), it.text.lowercase().replace(" ", "")) }
            .filter { it.second >= 0.8 }.maxByOrNull { it.second }?.first?.let { it to 1 }
    }
}

object AddToList : Tool(
    name = "add_to_list",
    title = "Lists",
    icon = R.drawable.ic_checklist,
    description = "Add things to a list (a note with checkboxes in your notes folder), such as the shopping list.",
    onByDefault = false,
    defaultRunFrom = 0.4,
    examples = listOf("Add milk and eggs to the shopping list", "Put call the plumber on my to-do list"),
) {
    override val keywords = listOf("list", "lists", "to-do", "todo", "shopping")

    override val schema = """
        {"name": "add_to_list",
         "description": "Add items to a list, such as add milk to the shopping list",
         "parameters": {"type": "object", "properties": {
           "list": {"type": "string", "description": "The list's name, such as shopping or to-do"},
           "items": {"type": "string", "description": "What to add, in the request's words, such as milk, eggs"}},
           "required": ["list", "items"]}}
    """.trimIndent()

    override fun grounded(arguments: Map<String, Any?>, request: String, time: FerbTime?) = Tools.mostlySaid(arguments.text("items"), request)

    override fun prepare(arguments: Map<String, Any?>, env: PrepareEnv): Prepared {
        val list = arguments.text("list") ?: return Prepared.Invalid("Which list?")
        val items = Lists.items(arguments.text("items") ?: return Prepared.Invalid("Nothing to add"))
        if (items.isEmpty()) return Prepared.Invalid("Nothing to add")
        if (!env.notes.ready) return Lists.needsFolder()
        val found = NoteStore.find(list, env.notes.list())
        val name = found?.name ?: Lists.title(list)
        return Prepared.Ready("Add ${items.joinToString()} to $name", opensApp = false, detail = "A new list".takeIf { found == null }) { _ ->
            val note = found ?: env.notes.create(name, CreateNote.noteFile("", NoteStore.newId()).trimEnd('\n') + "\n")
                ?: return@Ready Outcome.Failed("Couldn't make the list in your notes folder")
            var added = ""
            val ok = env.notes.update(note) { now -> NoteText.append(now, items.map { "- [ ] $it" }).also { added = it.substring(now.length) } }
            if (!ok) return@Ready Outcome.Failed("Couldn't change “${note.name}”")
            Outcome.Done("Added ${items.joinToString()} to ${note.name}", actions = listOf(
                FollowUp("Undo") { _ ->
                    // Only while the added lines are still the file's last.
                    val undone = env.notes.update(note) { now -> if (added.isNotEmpty() && now.endsWith(added)) now.dropLast(added.length) else now }
                    if (undone) Outcome.Done("Removed them from ${note.name}") else Outcome.Failed("Couldn't change “${note.name}”")
                },
                FollowUp("Show list") { _ -> Lists.shown(env.notes, note, env.openNote) },
            ))
        }
    }
}

object ShowList : Tool(
    name = "show_list",
    title = "Show lists",
    icon = R.drawable.ic_checklist,
    description = "Show what's left on a list from your notes folder, here in the chat, to tick off.",
    onByDefault = false,
    defaultRunFrom = 0.3,
    examples = listOf("What's on my shopping list?", "Show my to-do list"),
) {
    override val keywords = listOf("list", "lists", "to-do", "todo", "shopping")

    override val schema = """
        {"name": "show_list",
         "description": "Show a list, such as what's on my shopping list",
         "parameters": {"type": "object", "properties": {
           "list": {"type": "string", "description": "The list's name, such as shopping or to-do"}},
           "required": ["list"]}}
    """.trimIndent()

    override fun prepare(arguments: Map<String, Any?>, env: PrepareEnv): Prepared {
        val list = arguments.text("list") ?: return Prepared.Invalid("Which list?")
        if (!env.notes.ready) return Lists.needsFolder()
        val note = NoteStore.find(list, env.notes.list()) ?: return Prepared.Invalid("No list called “${Lists.title(list)}” in your notes folder")
        return Prepared.Ready("Show ${note.name}", opensApp = false) { _ -> Lists.shown(env.notes, note, env.openNote) }
    }
}

object CheckOff : Tool(
    name = "check_off",
    title = "Tick off",
    icon = R.drawable.ic_checklist,
    description = "Tick an item off a list in your notes folder, such as got the milk.",
    onByDefault = false,
    defaultRunFrom = 0.4,
    examples = listOf("Tick milk off the shopping list", "Cross off call the plumber"),
) {
    override val keywords = listOf("tick", "check off", "cross off", "got", "done with", "list")

    override val schema = """
        {"name": "check_off",
         "description": "Tick an item off a list, such as cross milk off the shopping list",
         "parameters": {"type": "object", "properties": {
           "list": {"type": "string", "description": "The list's name, such as shopping or to-do"},
           "item": {"type": "string", "description": "The item, in the request's words"}},
           "required": ["item"]}}
    """.trimIndent()

    override fun grounded(arguments: Map<String, Any?>, request: String, time: FerbTime?) = Tools.mostlySaid(arguments.text("item"), request)

    override fun prepare(arguments: Map<String, Any?>, env: PrepareEnv): Prepared {
        val item = arguments.text("item") ?: return Prepared.Invalid("Which item?")
        if (!env.notes.ready) return Lists.needsFolder()
        val notes = env.notes.list()
        val named = arguments.text("list")?.let { NoteStore.find(it, notes) }
        val (note, task) = if (named != null) {
            named to (env.notes.read(named)?.let { Lists.match(it, item) } ?: return Prepared.Invalid("“$item” isn't on ${named.name}, or is ticked already"))
        } else {
            // No list named: the list where the item matches best; as good on two, which? (review P2-74).
            val found = notes.mapNotNull { n -> env.notes.read(n)?.let { Lists.best(it, item) }?.let { (task, how) -> Triple(n, task, how) } }
            val top = found.maxOfOrNull { it.third } ?: return Prepared.Invalid("No list has “$item” to tick")
            val best = found.filter { it.third == top }
            if (best.size > 1) return Prepared.Invalid("“$item” is on ${best.joinToString(" and ") { it.first.name }}: which list?")
            best.single().first to best.single().second
        }
        return Prepared.Ready("Tick “${task.text}” on ${note.name}", opensApp = false) { _ ->
            fun toggle(checked: Boolean) = env.notes.update(note) { now ->
                NoteText.tasks(now).firstOrNull { it.checked == checked && it.text == task.text }?.let { NoteText.flip(now, it.mark) } ?: now
            }
            if (!toggle(false)) return@Ready Outcome.Failed("Couldn't change “${note.name}”")
            Outcome.Done("Ticked “${task.text}” on ${note.name}", actions = listOf(
                FollowUp("Undo") { _ -> if (toggle(true)) Outcome.Done("“${task.text}” is back on ${note.name}") else Outcome.Failed("Couldn't change “${note.name}”") },
                FollowUp("Show list") { _ -> Lists.shown(env.notes, note, env.openNote) },
            ))
        }
    }
}

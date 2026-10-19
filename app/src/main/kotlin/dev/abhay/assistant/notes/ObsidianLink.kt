package dev.abhay.assistant.notes

/**
 * The `obsidian://open` link for a note in Ferb's notes folder (a Storage Access Framework document
 * URI). Obsidian on Android (1.14.4, checked 2026-10-19) opens notes only through these links: no
 * VIEW for .md files or content:// URIs. Pure, so it's unit-tested.
 *
 * `vault=<the picked folder's name>&file=<the note's path from that folder, without .md>`, the form
 * Obsidian's "Copy Obsidian URL" makes (https://obsidian.md/help/Extending+Obsidian/Obsidian+URI).
 * Obsidian names a vault after its folder, so this fits when the picked folder is the vault. Checked
 * on the Pixel 11 (2026-10-19): it opens the note. `path=<absolute path>` opens Obsidian but not the
 * note on Android (as https://forum.obsidian.md/t/80640 reports), so it isn't used. Null: Ferb's viewer.
 */
object ObsidianLink {
    fun of(documentUri: String, noteName: String): String? {
        val (_, documentId) = parse(documentUri) ?: return null
        val tree = treeId(documentUri) ?: return null
        val vault = tree.substringAfter(':').substringAfterLast('/').ifEmpty { return null }
        // The note's path inside the folder; just its name when the provider's ids aren't paths.
        val inside = documentId.removePrefix("$tree/").takeIf { it != documentId && it.isNotEmpty() } ?: "$noteName.md"
        val file = inside.removeSuffix(".md").removeSuffix(".MD")
        return "obsidian://open?vault=${encode(vault)}&file=${encode(file)}"
    }

    /** The authority and document id of `content://<authority>/tree/<tree id>/document/<document id>`. */
    fun parse(documentUri: String): Pair<String, String>? {
        if (!documentUri.startsWith("content://")) return null
        val rest = documentUri.removePrefix("content://")
        val authority = rest.substringBefore('/')
        val id = rest.substringAfter("/document/", "").ifEmpty { return null }
        return authority to decode(id)
    }

    /** The picked folder's tree id ("primary:Documents/My Vault"), decoded. */
    private fun treeId(documentUri: String): String? =
        documentUri.substringAfter("/tree/", "").substringBefore('/').ifEmpty { null }?.let(::decode)

    /** Percent-encodes all but unreserved characters, as UTF-8: "/" as %2F and " " as %20, as Obsidian asks. */
    fun encode(text: String): String = buildString {
        for (b in text.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            if (c.toChar() in 'A'..'Z' || c.toChar() in 'a'..'z' || c.toChar() in '0'..'9' || c.toChar() in "-_.~") append(c.toChar())
            else append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 15])
        }
    }

    /** Percent-decoding only ("+" stays "+": SAF writes spaces as %20, and "+" can be in a name). */
    private fun decode(text: String): String {
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val hex = if (c == '%' && i + 2 < text.length) text.substring(i + 1, i + 3).toIntOrNull(16) else null
            if (hex != null) {
                out.write(hex)
                i += 3
            } else {
                out.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
            }
        }
        return out.toString(Charsets.UTF_8.name())
    }
}

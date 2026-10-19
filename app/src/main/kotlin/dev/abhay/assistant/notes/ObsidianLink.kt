package dev.abhay.assistant.notes

/**
 * The `obsidian://open` link for a note in Ferb's notes folder (a Storage Access Framework document
 * URI). Obsidian on Android (1.14.4, checked 2026-10-19) opens notes only through these links: no
 * VIEW for .md files or content:// URIs. Pure, so it's unit-tested.
 *
 * Tried in order (https://obsidian.md/help/Extending+Obsidian/Obsidian+URI):
 * 1. `path=<absolute path>`: Obsidian finds the vault that holds it, so the folder can be the vault
 *    or a folder inside it. Only for the phone's own storage and SD cards, whose documents have paths.
 * 2. `vault=<folder name>&file=<note name>`: other providers; Obsidian names a vault after its folder,
 *    so this fits when the picked folder is the vault.
 * 3. null: Ferb's own viewer.
 */
object ObsidianLink {
    private const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"

    fun of(documentUri: String, noteName: String): String? {
        val (authority, documentId) = parse(documentUri) ?: return null
        path(authority, documentId)?.let { return "obsidian://open?path=${encode(it)}" }
        val folder = folder(documentUri) ?: return null
        return "obsidian://open?vault=${encode(folder)}&file=${encode(noteName)}"
    }

    /** The absolute file path of an external-storage document ("primary:Documents/V/a.md"), or null. */
    fun path(authority: String, documentId: String): String? {
        if (authority != EXTERNAL_STORAGE) return null
        val volume = documentId.substringBefore(':', "").ifEmpty { return null }
        val rest = documentId.substringAfter(':')
        return when {
            volume == "primary" -> "/storage/emulated/0/$rest"
            volume == "home" -> "/storage/emulated/0/Documents/$rest"
            // An SD card or USB drive: its volume id, e.g. "1234-ABCD".
            Regex("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}").matches(volume) -> "/storage/$volume/$rest"
            else -> null
        }
    }

    /** The authority and document id of `content://<authority>/tree/<tree id>/document/<document id>`. */
    fun parse(documentUri: String): Pair<String, String>? {
        if (!documentUri.startsWith("content://")) return null
        val rest = documentUri.removePrefix("content://")
        val authority = rest.substringBefore('/')
        val id = rest.substringAfter("/document/", "").ifEmpty { return null }
        return authority to decode(id)
    }

    /** The picked folder's name, from the tree id ("primary:Documents/My Vault" → "My Vault"). */
    private fun folder(documentUri: String): String? {
        val tree = documentUri.substringAfter("/tree/", "").substringBefore('/').ifEmpty { return null }
        return decode(tree).substringAfter(':').substringAfterLast('/').ifEmpty { null }
    }

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

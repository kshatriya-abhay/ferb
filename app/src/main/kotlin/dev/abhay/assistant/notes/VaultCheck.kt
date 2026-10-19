package dev.abhay.assistant.notes

/**
 * Whether the picked notes folder is an Obsidian vault's top folder: it holds a `.obsidian` entry.
 * Notes open by `vault=<folder name>&file=<path from it>`, so a folder inside a vault gives the
 * wrong vault and path, and the folder's grant can't see its parents to correct them.
 *
 * The entry is looked up by that exact name, never by listing the folder: on providers whose
 * document ids are paths (the phone's storage and SD cards), `.obsidian`'s id is the folder's id
 * plus "/.obsidian". Elsewhere it can't be told, and nothing is said.
 */
object VaultCheck {
    const val NAME = ".obsidian"
    const val NOT_A_VAULT = "This folder doesn't look like an Obsidian vault. Pick the vault's top folder so notes open in Obsidian."

    enum class Outcome { Vault, NotAVault, Unknown }

    private val PATH_IDS = setOf("com.android.externalstorage.documents")

    /** The document id `.obsidian` would have in the folder [treeDocumentId], or null when ids aren't paths. */
    fun entryId(authority: String, treeDocumentId: String): String? {
        if (authority !in PATH_IDS || !treeDocumentId.contains(':')) return null
        return if (treeDocumentId.endsWith(":")) "$treeDocumentId$NAME" else "$treeDocumentId/$NAME"
    }

    /** [exists]: whether the entry was found (null: it couldn't be looked up). */
    fun outcome(exists: Boolean?): Outcome = when (exists) {
        true -> Outcome.Vault
        false -> Outcome.NotAVault
        null -> Outcome.Unknown
    }

    /** The warning to show for [outcome], or null. */
    fun warning(outcome: Outcome): String? = if (outcome == Outcome.NotAVault) NOT_A_VAULT else null
}

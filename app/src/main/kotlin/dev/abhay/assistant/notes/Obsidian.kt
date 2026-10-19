package dev.abhay.assistant.notes

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Ferb's Markdown notes are opened in Obsidian ("Markdown notes (powered by Obsidian)"); Ferb has
 * no viewer or editor of its own. Seeing Obsidian needs only the manifest's `<queries>` entry for
 * the `obsidian` scheme, no permission. Saving, lists and reminders work through the Storage Access
 * Framework without it.
 */
object Obsidian {
    const val NOT_INSTALLED = "Install Obsidian to open this note"


    fun installed(context: Context): Boolean =
        context.packageManager.queryIntentActivities(Intent(Intent.ACTION_VIEW, Uri.parse("obsidian://open")), 0).isNotEmpty()

    /** Opens [note] in Obsidian ([ObsidianLink]); false when it can't (not installed, or no link). */
    fun open(context: Context, note: NoteRef): Boolean {
        val link = ObsidianLink.of(note.id, note.name) ?: return false
        return try {
            // A plain VIEW: it opens the note whether Obsidian is open or not (the Pixel 11,
            // 2026-10-19). NEW_TASK only because the caller may not be an activity.
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }
}

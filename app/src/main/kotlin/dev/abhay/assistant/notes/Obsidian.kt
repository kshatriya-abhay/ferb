package dev.abhay.assistant.notes

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Opening Ferb's notes in Obsidian, the user's notes app, instead of Ferb's own viewer and editor
 * (which stay as the fallback). Seeing Obsidian needs only the manifest's `<queries>` entry for
 * the `obsidian` scheme, no permission.
 */
object Obsidian {
    fun installed(context: Context): Boolean =
        context.packageManager.queryIntentActivities(Intent(Intent.ACTION_VIEW, Uri.parse("obsidian://open")), 0).isNotEmpty()

    /** Opens [note] in Obsidian ([ObsidianLink]); false when it can't, for Ferb's viewer to show it. */
    fun open(context: Context, note: NoteRef): Boolean {
        val link = ObsidianLink.of(note.id, note.name) ?: return false
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }
}

package dev.abhay.assistant.tools

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/** An app the launcher shows: its name and how to open it. */
data class LaunchableApp(val label: String, val packageName: String)

/**
 * The phone's launchable apps (the manifest's `<queries>` makes them visible), and finding one by a
 * spoken or typed name. Matching happens here, not in the model: the app names aren't in its
 * prompt, so Needle 3 passes the name as the user said it.
 */
class InstalledApps(private val load: () -> List<LaunchableApp>) {
    /** Reads the list on every call, so apps installed since are found too. */
    fun find(query: String): LaunchableApp? = best(query, load())

    companion object {
        fun from(context: Context) = InstalledApps {
            val pm = context.packageManager
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), PackageManager.ResolveInfoFlags.of(0))
                .map { LaunchableApp(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
                .filter { it.packageName != context.packageName }
                .distinctBy { it.packageName }
        }

        private fun normal(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

        /**
         * The best match for [query]: an exact name, then a name starting with it, then one
         * containing it, then the most shared words ("the camera app" finds "Camera"); shorter names
         * win ties. The word "app" itself is ignored. Null if nothing shares a word.
         */
        fun best(query: String, apps: List<LaunchableApp>): LaunchableApp? {
            val q = normal(query).split(' ').filter { it.isNotEmpty() && it != "app" && it != "the" }.joinToString(" ")
            if (q.isEmpty()) return null
            val named = apps.map { it to normal(it.label) }
            fun shortest(list: List<Pair<LaunchableApp, String>>) = list.minByOrNull { it.second.length }?.first
            named.filter { it.second == q }.let { if (it.isNotEmpty()) return shortest(it) }
            named.filter { it.second.startsWith(q) }.let { if (it.isNotEmpty()) return shortest(it) }
            named.filter { it.second.contains(q) }.let { if (it.isNotEmpty()) return shortest(it) }
            val words = q.split(' ').toSet()
            return named.map { (app, label) -> Triple(app, label, label.split(' ').count { it in words }) }
                .filter { it.third > 0 }
                .sortedWith(compareByDescending<Triple<LaunchableApp, String, Int>> { it.third }.thenBy { it.second.length })
                .firstOrNull()?.first
        }
    }
}

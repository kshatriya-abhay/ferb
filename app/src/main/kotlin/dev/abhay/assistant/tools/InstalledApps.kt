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

    /** Like [find], for a name that may be misheard; only after [find] and the settings pages found nothing. */
    fun findLoosely(query: String): LaunchableApp? = loose(query, load())

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

        /** Below this, a misheard name isn't taken to be an app's. */
        const val MIN_LOOSE = 0.75

        /**
         * The app a misheard or misspelt [query] most likely names, when [best] found none: spoken
         * requests come back as "ptm kholo" for "Paytm kholo", or "peti m" (Gemma 4 E2B, 2026-10-12).
         * Compared whole (words joined) and word by word, by spelling and sound as contacts are
         * ([ContactMatch.word]), and by consonants alone ("ptm" and "Paytm" are both p-t-m). Only
         * names of 3+ letters, and only a clear winner, so short words don't open an app by chance.
         */
        fun loose(query: String, apps: List<LaunchableApp>): LaunchableApp? {
            val words = normal(query).split(' ').filter { it.isNotEmpty() && it !in FILLER }
            if (words.isEmpty()) return null
            // The whole name first; one word of several counts for less ("fone pe" is PhonePe, not Phone).
            val whole = words.joinToString("")
            val parts = if (words.size > 1) words.filter { it.length >= 3 } else emptyList()
            if (whole.length < 3 && parts.isEmpty()) return null
            val scored = apps.map { app ->
                val label = normal(app.label).replace(" ", "")
                app to if (label.length < 3) 0.0 else maxOf(
                    if (whole.length >= 3) looseScore(whole, label) else 0.0,
                    parts.maxOfOrNull { 0.9 * looseScore(it, label) } ?: 0.0,
                )
            }.sortedByDescending { it.second }
            val (top, score) = scored.firstOrNull() ?: return null
            val next = scored.getOrNull(1)?.second ?: 0.0
            return top.takeIf { score >= MIN_LOOSE && score - next >= 0.05 }
        }

        private fun looseScore(said: String, label: String): Double {
            val skeleton = consonants(said)
            return maxOf(if (skeleton.length >= 3 && skeleton == consonants(label)) 0.9 else 0.0, ContactMatch.word(said, label))
        }

        /** A word's sound ([ContactMatch.sound]) without its vowels after the first letter: "paytm" and "peti m" are both "ptm". */
        internal fun consonants(word: String): String {
            val sound = ContactMatch.sound(word)
            return sound.first() + sound.drop(1).filter { it !in "aeiouy" }
        }

        // Words around an app's name, in English and Hindi ("ptm kholo": open Paytm).
        private val FILLER = setOf("app", "the", "open", "launch", "start", "kholo", "khol", "kholna", "chalao", "chalu", "karo", "do", "please")
    }
}

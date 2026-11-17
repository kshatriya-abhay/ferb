package dev.abhay.assistant

/**
 * What a request said, for logcat. Debug builds log only its length, as release does (src/release),
 * unless opted in for the run: `adb shell setprop log.tag.FerbWords VERBOSE`, read once at start
 * and gone at the next reboot. Review P2-185: the debug build is the one on the user's phone, and
 * the mic's words (a room's talk included) went to logcat in full.
 */
object Private {
    /** Opted in for this run (Android's per-tag log level; false where there's no Android, in JVM tests). */
    private val words: Boolean by lazy { runCatching { android.util.Log.isLoggable(WORDS_TAG, android.util.Log.VERBOSE) }.getOrDefault(false) }

    const val WORDS_TAG = "FerbWords"

    fun text(said: String): String = text(said, words)

    internal fun text(said: String, words: Boolean): String = if (words) "\"$said\"" else "(${said.length} chars)"
}

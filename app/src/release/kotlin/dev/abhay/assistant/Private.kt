package dev.abhay.assistant

/**
 * What a request said, for logcat: release builds log only its length, never the words (a
 * request, its transcript or its rewrite); debug builds log them (src/debug).
 */
object Private {
    fun text(said: String): String = "(${said.length} chars)"
}

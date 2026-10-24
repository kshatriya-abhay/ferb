package dev.abhay.assistant

/** What a request said, for logcat: debug builds log it as it is (release builds only its length, src/release). */
object Private {
    fun text(said: String): String = "\"$said\""
}

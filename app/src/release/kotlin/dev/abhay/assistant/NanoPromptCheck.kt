package dev.abhay.assistant

/** The Gemini Nano prompt check is in debug builds only (src/debug); release builds have no Prompt API. */
object NanoPromptCheck {
    const val AVAILABLE = false
    const val TITLE = ""

    suspend fun run(): String = ""
}

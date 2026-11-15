package dev.abhay.assistant.voice

import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import java.security.MessageDigest
import org.junit.Test

/**
 * Rewrite prompt R and R2 are frozen: the content work's frozen code (build/content/frozen-2,
 * content_exp.py, 2026-11-06) reads them out of NanoVoice.kt's source with two regexes, and its
 * results were measured on them. So their text and their layout in the file stay as they are; a
 * change is a new prompt beside them, with its own version.
 */
class FrozenPromptsTest {
    private val source = File("src/main/kotlin/dev/abhay/assistant/voice/NanoVoice.kt").readText()
    private val why = "frozen content code (build/content/frozen-2/content_exp.py) reads this from NanoVoice.kt: add a new prompt instead of changing it"

    private fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    @Test
    fun promptsRAndR2AreAsFrozen() {
        assertWithMessage("PROMPT_R: $why").that(sha256(GeminiRewrite.PROMPT_R)).isEqualTo("fcae78dd9954bddf26348e186c95870b85a743958793aad4f6a38b908d02e00b")
        assertWithMessage("PROMPT_R2: $why").that(sha256(GeminiRewrite.PROMPT_R2)).isEqualTo("eef3336d56d67e648d1e7478553a05f211a79596d7f90fba4dc1bddf947a84b1")
    }

    @Test
    fun theFrozenCodeStillFindsThemInTheSource() {
        // content_exp.py's own two regexes.
        val r = Regex("""const val PROMPT_R = \"\"\"(.*?)\"\"\"""", RegexOption.DOT_MATCHES_ALL).find(source)
        assertWithMessage("PROMPT_R's declaration: $why").that(r?.groupValues?.get(1)).isEqualTo(GeminiRewrite.PROMPT_R)
        val replaces = Regex("""val PROMPT_R2 = PROMPT_R\.replace\((.*?)\n    \)\n""", RegexOption.DOT_MATCHES_ALL).find(source)
        assertWithMessage("PROMPT_R2's replace block: $why").that(replaces).isNotNull()
    }
}

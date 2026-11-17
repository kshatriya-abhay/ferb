package dev.abhay.assistant

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Release builds log a request's length only, never its words (review P2-177; debug: PrivateDebugTest). */
class PrivateTest {
    @Test
    fun releaseLogsNoWords() {
        val said = "kal subah saat baje yaad dilana"
        val logged = Private.text(said)
        assertThat(logged).isEqualTo("(${said.length} chars)")
        for (word in said.split(" ")) assertThat(logged).doesNotContain(word)
    }
}

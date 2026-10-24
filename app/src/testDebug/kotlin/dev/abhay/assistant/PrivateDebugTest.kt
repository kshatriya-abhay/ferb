package dev.abhay.assistant

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Debug builds log what a request said, for the bench and debugging (release: PrivateTest in testRelease). */
class PrivateDebugTest {
    @Test
    fun debugLogsTheWords() {
        assertThat(Private.text("kal yaad dilana")).isEqualTo("\"kal yaad dilana\"")
    }
}

package dev.abhay.assistant

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Debug builds log a request's length by default, as release does (review P2-185: the mic's words,
 * a room's talk included, went to logcat in full); its words only when opted in for the run
 * (`adb shell setprop log.tag.FerbWords VERBOSE`, gone at reboot). Release: src/testRelease.
 */
class PrivateDebugTest {
    @Test
    fun debugLogsOnlyTheLengthByDefault() {
        val said = "kal subah saat baje yaad dilana"
        val logged = Private.text(said)
        assertThat(logged).isEqualTo("(${said.length} chars)")
        for (word in said.split(" ")) assertThat(logged).doesNotContain(word)
    }

    @Test
    fun theWordsOnlyWhenOptedIn() {
        assertThat(Private.text("kal yaad dilana", words = true)).isEqualTo("\"kal yaad dilana\"")
        assertThat(Private.text("kal yaad dilana", words = false)).isEqualTo("(15 chars)")
    }
}

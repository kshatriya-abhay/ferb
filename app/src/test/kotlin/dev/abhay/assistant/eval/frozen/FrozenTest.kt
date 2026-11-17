package dev.abhay.assistant.eval.frozen

import com.google.common.truth.Truth.assertThat
import dev.abhay.assistant.eval.DateReaders
import java.io.File
import java.time.LocalDateTime
import org.junit.Test

/** Review P2-169: the frozen readers stay as they were at their freeze. */
class FrozenTest {
    private val now = LocalDateTime.of(2026, 10, 24, 10, 0)

    @Test
    fun theFrozenReadersTimeATimerAsAtTheFreeze() {
        // At the freeze, a word Durations couldn't read was dropped and the rest summed:
        // "quarter" unread (ask), "1 hour 30" an hour, "2 minutes 10" two minutes.
        for (reader in listOf(DateReaders.A0, DateReaders.A1)) {
            assertThat(reader.read("set a timer for a quarter of an hour", "set_timer", now)).isEqualTo("ask")
            assertThat(reader.read("set a timer for 1 hour 30", "set_timer", now)).isEqualTo("+PT1H")
            assertThat(reader.read("set a timer for 2 minutes 10", "set_timer", now)).isEqualTo("+PT2M")
        }
    }

    @Test
    fun frozenCodeImportsNothingLive() {
        // A frozen reader that calls live code moves when the live code does.
        val live = File("src/test/kotlin/dev/abhay/assistant/eval/frozen").listFiles { f -> f.extension == "kt" }!!
            .flatMap { f -> f.readLines().filter { it.startsWith("import dev.abhay.assistant.") && !it.startsWith("import dev.abhay.assistant.eval.frozen.") }.map { "${f.name}: $it" } }
            .filter { !it.startsWith("FrozenTest.kt") }
        assertThat(live).isEmpty()
    }
}

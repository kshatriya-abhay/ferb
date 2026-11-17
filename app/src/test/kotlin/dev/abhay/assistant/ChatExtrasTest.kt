package dev.abhay.assistant

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Review P2-157: what the front door hands the chat from an intent any app can send it. */
class ChatExtrasTest {
    @Test
    fun ferbsOwnExtrasPassCheckedAndNothingOdd() {
        assertThat(ChatExtras.of(true, null, null)).isEqualTo(ChatExtras(true, null))
        assertThat(ChatExtras.of(false, "content://notes/a.md", "a").note).isEqualTo("content://notes/a.md" to "a")
        // Blank, control characters or past the length: not a note.
        assertThat(ChatExtras.of(false, " ", "a").note).isNull()
        assertThat(ChatExtras.of(false, "a\u0000b", "a").note).isNull()
        assertThat(ChatExtras.of(false, "x".repeat(2_000), "a").note).isNull()
        // A bad name only loses the name.
        assertThat(ChatExtras.of(false, "id", "bad\nname").note).isEqualTo("id" to "")
    }
}

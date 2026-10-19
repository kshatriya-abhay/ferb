package dev.abhay.assistant.link

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The chat box's Phone/Computer labels: "Phone + Jev" while Jev picks the tool. */
class AnswererLabelTest {
    @Test
    fun phoneSaysJevOnlyWhileJevIsInUse() {
        assertThat(Answerer.Needle.label(usingJev = true)).isEqualTo("Phone + Jev")
        assertThat(Answerer.Needle.label(usingJev = false)).isEqualTo("Phone")
        // Computer's label stays as it is either way.
        assertThat(Answerer.Link.label(usingJev = true)).isEqualTo("Computer")
        assertThat(Answerer.Link.label(usingJev = false)).isEqualTo("Computer")
    }

    @Test
    fun usingJevIsSaidOnceNotTwice() {
        // Phone picked: its label says it, so no line under the box.
        assertThat(Answerer.usingJevLine(usingJev = true, shown = Answerer.Needle)).isFalse()
        // Computer picked, or no choice in the box ("When Needle 3 is unsure"): the line says it.
        assertThat(Answerer.usingJevLine(usingJev = true, shown = Answerer.Link)).isTrue()
        assertThat(Answerer.usingJevLine(usingJev = true, shown = null)).isTrue()
        // Jev not in use: never.
        assertThat(Answerer.usingJevLine(usingJev = false, shown = Answerer.Link)).isFalse()
        assertThat(Answerer.usingJevLine(usingJev = false, shown = null)).isFalse()
    }
}

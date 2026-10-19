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
}

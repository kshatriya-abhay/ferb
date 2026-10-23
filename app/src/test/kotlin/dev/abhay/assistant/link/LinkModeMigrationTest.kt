package dev.abhay.assistant.link

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** "When Needle 3 is unsure" is gone: what's left, and where a saved one goes. */
class LinkModeMigrationTest {
    @Test
    fun onlyOffAndTheChatsChoiceAreLeft() {
        assertThat(LinkMode.entries).containsExactly(LinkMode.Off, LinkMode.Option)
    }

    @Test
    fun aSavedWhenUnsureBecomesTheChoiceWithPhonePicked() {
        val changes = LinkSettings.migration("fallback")!!
        assertThat(LinkMode.of(changes.getValue("mode"))).isEqualTo(LinkMode.Option)
        assertThat(Answerer.of(changes.getValue("answerer"))).isEqualTo(Answerer.Needle)
    }

    @Test
    fun otherSettingsAreLeftAlone() {
        assertThat(LinkSettings.migration("off")).isNull()
        assertThat(LinkSettings.migration("option")).isNull()
        assertThat(LinkSettings.migration(null)).isNull()
        // Once migrated, nothing more to do.
        assertThat(LinkSettings.migration(LinkSettings.migration("fallback")!!.getValue("mode"))).isNull()
    }
}

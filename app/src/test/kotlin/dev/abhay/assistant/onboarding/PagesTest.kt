package dev.abhay.assistant.onboarding

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The first launch's four pages: their order, the assistant page only when needed, and setup again. */
class PagesTest {
    @Test
    fun theWelcomeThenTheModelThenToolsThenTheAssistant() {
        assertThat(Page.shown(assistantHeld = false)).containsExactly(Page.Welcome, Page.Model, Page.Tools, Page.Assistant).inOrder()
        assertThat(Page.next(Page.Welcome, false)).isEqualTo(Page.Model)
        assertThat(Page.next(Page.Model, false)).isEqualTo(Page.Tools)
        assertThat(Page.next(Page.Tools, false)).isEqualTo(Page.Assistant)
        assertThat(Page.previous(Page.Assistant, false)).isEqualTo(Page.Tools)
        assertThat(Page.previous(Page.Tools, false)).isEqualTo(Page.Model)
        assertThat(Page.next(Page.Assistant, false)).isNull()
        assertThat(Page.previous(Page.Model, false)).isEqualTo(Page.Welcome)
        assertThat(Page.previous(Page.Welcome, false)).isNull()
    }

    @Test
    fun ferbAlreadyTheAssistantSkipsThatPage() {
        assertThat(Page.shown(assistantHeld = true)).doesNotContain(Page.Assistant)
        assertThat(Page.next(Page.Model, true)).isEqualTo(Page.Tools)
        assertThat(Page.next(Page.Tools, true)).isNull()
        // Made the assistant from its own page: that page ends it, and back goes to the tools.
        assertThat(Page.next(Page.Assistant, true)).isNull()
        assertThat(Page.previous(Page.Assistant, true)).isEqualTo(Page.Tools)
    }

    @Test
    fun setupAgainKeepsTheTipsSeen() {
        var stored = OnboardingState(welcomed = true, seen = setOf(Tip.Details))
        val onboarding = Onboarding(load = { stored }, store = { stored = it })
        onboarding.rerun()
        assertThat(stored).isEqualTo(OnboardingState(welcomed = false, seen = setOf(Tip.Details)))
        onboarding.reset()
        assertThat(stored).isEqualTo(OnboardingState())
    }
}

package dev.abhay.assistant.onboarding

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OnboardingTest {
    @Test
    fun oneTipAtATimeByPriorityAndNeverTwice() {
        // A reply that fell back, with the mic shown: Details comes before Fallback.
        val onScreen = listOf(Tip.Fallback, Tip.Details)
        assertThat(Tip.next(onScreen, seen = emptySet())).isEqualTo(Tip.Details)
        assertThat(Tip.next(onScreen, seen = setOf(Tip.Details))).isEqualTo(Tip.Fallback)
        assertThat(Tip.next(onScreen, seen = setOf(Tip.Details, Tip.Fallback))).isNull()
        // Only what's on screen: nothing shows for a thing that isn't.
        assertThat(Tip.next(emptyList(), seen = emptySet())).isNull()
    }

    @Test
    fun theWelcomeAndTipsAreRememberedAndCanBeShownAgain() {
        var stored = OnboardingState()
        val onboarding = Onboarding(load = { stored }, store = { stored = it })
        assertThat(onboarding.state.value.welcomed).isFalse()

        onboarding.welcomed()
        onboarding.seen(Tip.Details)
        onboarding.seen(Tip.Details)
        assertThat(stored).isEqualTo(OnboardingState(welcomed = true, seen = setOf(Tip.Details)))
        // Next launch: the same.
        assertThat(Onboarding(load = { stored }, store = { stored = it }).state.value).isEqualTo(stored)

        // "Show tips again": as on the first launch, welcome screen included.
        onboarding.reset()
        assertThat(stored).isEqualTo(OnboardingState())
        assertThat(onboarding.state.value).isEqualTo(OnboardingState())
    }
}

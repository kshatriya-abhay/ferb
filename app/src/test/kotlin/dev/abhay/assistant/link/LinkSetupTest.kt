package dev.abhay.assistant.link

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The Ferb Link checklist on Ferb's settings page ticks itself off from what Ferb can see. */
class LinkSetupTest {
    private fun done(steps: List<SetupStep>) = steps.filter { it.done }.map { it.title }

    @Test
    fun notInstalledStartsAtTheDownload() {
        val steps = setupSteps(LinkStatus.NotInstalled, answers = null)
        assertThat(steps.first().action).isEqualTo(SetupAction.Download)
        assertThat(done(steps)).isEmpty()
        // Nothing to open yet.
        assertThat(steps.map { it.action }).doesNotContain(SetupAction.OpenFerbLink)
    }

    @Test
    fun installedButNotPairedOpensFerbLinkToPair() {
        val steps = setupSteps(LinkStatus.NotSetUp, answers = null)
        assertThat(done(steps)).containsExactly("Install Ferb Link")
        assertThat(steps.first { it.title.startsWith("Pair") }.action).isEqualTo(SetupAction.OpenFerbLink)
    }

    @Test
    fun pairedButSwitchedOffSaysToTurnItOn() {
        val steps = setupSteps(LinkStatus.Off, answers = null)
        assertThat(steps.map { it.title }).contains("Turn on “Answer Ferb's requests”")
        assertThat(done(steps)).containsAtLeast("Install Ferb Link", "Pair Ferb Link with your computer")
    }

    @Test
    fun readyButNotAnsweringPointsAtTailscaleAndTheServer() {
        val steps = setupSteps(LinkStatus.Ready("gemma-4-e2b"), answers = false)
        assertThat(steps.first { it.title == "Your computer answers" }.detail).contains("Tailscale")
        assertThat(steps.first { it.title.startsWith("Tailscale") }.done).isFalse()
    }

    @Test
    fun workingNeedsNoChecklist() {
        assertThat(setupSteps(LinkStatus.Ready("gemma-4-e2b"), answers = true)).isEmpty()
    }
}

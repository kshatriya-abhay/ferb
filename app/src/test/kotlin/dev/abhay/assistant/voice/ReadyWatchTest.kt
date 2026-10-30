package dev.abhay.assistant.voice

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReadyWatchTest {
    private val downloading = GeminiSetup(Part.Downloading, Part.Downloading)
    private val ready = GeminiSetup(Part.Ready, Part.Ready)

    /** A watch whose checks answer from [answers] in turn (the last one repeats), noting when each was asked. */
    private class Rig(scope: TestScope, answers: List<GeminiSetup>, front: Boolean = true) {
        val inFront = MutableStateFlow(front)
        val asked = mutableListOf<Long>()
        private val left = ArrayDeque(answers)
        val watch = ReadyWatch(scope.backgroundScope, inFront, { scope.testScheduler.currentTime }) {
            asked += scope.testScheduler.currentTime
            if (left.size > 1) left.removeFirst() else left.first()
        }
    }

    @Test
    fun asksEveryTenSecondsThenBacksOffWhileNothingChanges() = runTest {
        val rig = Rig(this, listOf(downloading))
        rig.watch.start()
        advanceTimeBy(130_001)
        // 10 s, then half as long again each time nothing changed, up to 30 s.
        assertThat(rig.asked).containsExactly(10_000L, 20_000L, 35_000L, 57_500L, 87_500L, 117_500L).inOrder()
        assertThat(rig.watch.state.value).isEqualTo(GeminiWatch.Watching)
    }

    @Test
    fun aChangeBringsTheWaitBack() {
        assertThat(ReadyWatch.next(30_000, changed = true)).isEqualTo(ReadyWatch.FIRST_MS)
        assertThat(ReadyWatch.next(10_000, changed = false)).isEqualTo(15_000)
        assertThat(ReadyWatch.next(25_000, changed = false)).isEqualTo(ReadyWatch.MAX_MS)
    }

    @Test
    fun readyWhenAiCoreSaysSoAndThenStopsAsking() = runTest {
        val rig = Rig(this, listOf(downloading, ready))
        rig.watch.start()
        advanceTimeBy(200_000)
        assertThat(rig.watch.state.value).isEqualTo(GeminiWatch.Ready)
        assertThat(rig.asked).hasSize(2)
    }

    @Test
    fun theDownloadFinishingAsksAtOnce() = runTest {
        val rig = Rig(this, listOf(ready))
        rig.watch.start()
        advanceTimeBy(1_000)
        rig.watch.nudge()
        runCurrent()
        assertThat(rig.asked).containsExactly(1_000L)
        assertThat(rig.watch.state.value).isEqualTo(GeminiWatch.Ready)
    }

    @Test
    fun awayFromFerbItDoesntAskThenAsksOnComingBack() = runTest {
        val rig = Rig(this, listOf(downloading, ready), front = false)
        rig.watch.start()
        advanceTimeBy(600_000)
        assertThat(rig.asked).isEmpty()
        rig.inFront.value = true
        runCurrent()
        assertThat(rig.asked).containsExactly(600_000L)
    }

    @Test
    fun aFailedPartStopsIt() = runTest {
        val rig = Rig(this, listOf(GeminiSetup(Part.Failed("not enough space on the phone"), Part.Downloading)))
        rig.watch.start()
        advanceTimeBy(200_000)
        assertThat(rig.watch.state.value).isEqualTo(GeminiWatch.Off)
        assertThat(rig.asked).hasSize(1)
    }

    @Test
    fun afterHalfAnHourOfAskingItGivesUp() = runTest {
        val rig = Rig(this, listOf(downloading))
        rig.watch.start()
        advanceTimeBy(ReadyWatch.CAP_MS + 60_000)
        assertThat(rig.watch.state.value).isEqualTo(GeminiWatch.GaveUp)
        val asked = rig.asked.size
        advanceTimeBy(600_000)
        assertThat(rig.asked).hasSize(asked)
        // Try again (or a download started again) waits once more.
        rig.watch.start()
        assertThat(rig.watch.state.value).isEqualTo(GeminiWatch.Watching)
    }

    @Test
    fun timeAwayDoesntCountTowardsGivingUp() = runTest {
        val rig = Rig(this, listOf(downloading), front = false)
        rig.watch.start()
        advanceTimeBy(ReadyWatch.CAP_MS * 2)
        rig.inFront.value = true
        advanceTimeBy(60_000)
        assertThat(rig.watch.state.value).isEqualTo(GeminiWatch.Watching)
    }

    @Test
    fun readySeenElsewhereEndsTheWait() = runTest {
        val rig = Rig(this, listOf(downloading))
        rig.watch.sawReady()
        assertThat(rig.watch.state.value).isEqualTo(GeminiWatch.Off) // Wasn't waiting: nothing to say.
        rig.watch.start()
        rig.watch.sawReady()
        assertThat(rig.watch.state.value).isEqualTo(GeminiWatch.Ready)
        advanceTimeBy(100_000)
        assertThat(rig.asked).isEmpty()
    }

    @Test
    fun checksStayHalfASecondApart() {
        assertThat(ReadyWatch.pace(now = 1_200, last = 1_000)).isEqualTo(300)
        assertThat(ReadyWatch.pace(now = 2_000, last = 1_000)).isEqualTo(0)
        assertThat(ReadyWatch.pace(now = 0, last = Long.MIN_VALUE / 2)).isEqualTo(0)
    }

    @Test
    fun whatStopsTheWait() {
        assertThat(ReadyWatch.stops(GeminiSetup(Part.Unavailable, Part.Downloading))).isTrue()
        assertThat(ReadyWatch.stops(GeminiSetup(Part.Unchecked("NullPointerException"), Part.Downloading))).isTrue()
        assertThat(ReadyWatch.stops(GeminiSetup(Part.NeedsDownload, Part.Downloading))).isFalse()
        assertThat(ReadyWatch.stops(downloading)).isFalse()
    }
}

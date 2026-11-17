package dev.abhay.assistant.link

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Review P2-115: Ferb Link's client, held while a step uses the computer. */
class HeldTest {
    private class Client(val n: Int) { var closed = false }

    private val tasks = mutableListOf<Runnable>()
    private var made = 0
    private var onAcquire: () -> Unit = {}
    private val held = Held(
        acquire = { onAcquire(); Client(++made) },
        close = { it.closed = true },
        background = { tasks += it },
    )

    private fun runBackground() { while (tasks.isNotEmpty()) tasks.removeAt(0).run() }

    @Test
    fun holdingReturnsBeforeTheClientIsAcquired() {
        held.hold()
        // The caller (Main) doesn't wait for Ferb Link's process to start.
        assertThat(made).isEqualTo(0)
        runBackground()
        assertThat(held.current()?.n).isEqualTo(1)
    }

    @Test
    fun aClientAcquiredAfterTheLastReleaseIsClosed() {
        held.hold()
        onAcquire = { held.release() } // the screen goes before Ferb Link answers
        runBackground()
        assertThat(held.current()).isNull()
        assertThat(made).isEqualTo(1)
    }

    @Test
    fun aReacquireRacingReleaseLeaksNothing() {
        held.hold(); runBackground()
        val dead = held.current()!!
        onAcquire = { held.release() } // released while the dead client's replacement is acquired
        held.died(dead)
        assertThat(dead.closed).isTrue()
        assertThat(held.current()).isNull()
        assertThat(made).isEqualTo(2)
    }

    @Test
    fun aDeadClientIsReplacedWhileHeldAndClosedOnce() {
        held.hold(); runBackground()
        val dead = held.current()!!
        held.died(dead)
        held.died(dead) // a second call that saw it die too
        assertThat(held.current()?.n).isEqualTo(2)
        assertThat(made).isEqualTo(2)
        held.release()
        assertThat(held.current()).isNull()
    }
}

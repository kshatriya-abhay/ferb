package dev.abhay.assistant.tools

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** A result's slider following a value changed outside Ferb (the volume keys). */
class LevelWatchTest {
    @Test
    fun aChangeOutsideFerbMovesTheSlider() {
        assertThat(LevelWatch.follow(shown = 0.4f, now = 0.6f, dragging = false)).isEqualTo(0.6f)
        assertThat(LevelWatch.follow(shown = 0.4f, now = 0f, dragging = false)).isEqualTo(0f)
    }

    @Test
    fun theFingerWinsWhileDragging() {
        assertThat(LevelWatch.follow(shown = 0.4f, now = 0.6f, dragging = true)).isNull()
    }

    @Test
    fun ferbsOwnSettingIsntAChange() {
        // Setting 0.402 lands on the stream's step 0.4: its echo leaves the slider alone.
        assertThat(LevelWatch.follow(shown = 0.402f, now = 0.4f, dragging = false)).isNull()
        assertThat(LevelWatch.follow(shown = 0.4f, now = 0.4f, dragging = false)).isNull()
    }

    @Test
    fun outOfRangeIsClamped() {
        assertThat(LevelWatch.follow(shown = 0.5f, now = 1.2f, dragging = false)).isEqualTo(1f)
    }
}

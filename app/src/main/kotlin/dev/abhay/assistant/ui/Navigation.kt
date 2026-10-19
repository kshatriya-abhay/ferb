package dev.abhay.assistant.ui

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch

/** Which way a move between screen stacks goes: back when the new stack is shorter (popped or reset to the chat). */
object Nav {
    /** Back's length: the system's standard short duration. */
    const val BACK_MS = 300

    fun isBack(from: List<String>, to: List<String>): Boolean = to.size < from.size

    /**
     * Forward: Material's shared X axis, the new screen sliding in 30 dp from the end while the old
     * one slides the other way, both fading (https://m3.material.io/styles/motion/transitions/transition-patterns).
     * Back, also as the predictive back gesture runs: the screen leaving shrinks to 90% and fades
     * over the one beneath, which slides back in
     * (https://developer.android.com/design/ui/mobile/guides/patterns/predictive-back).
     * Forward timings are the theme's Expressive motion scheme: spatial springs for movement,
     * effects springs for fades (https://m3.material.io/styles/motion/overview/specs). Back uses
     * linear tweens, as the gesture drives it.
     */
    fun transform(back: Boolean, shift: Int, motion: MotionScheme): ContentTransform {
        val move = motion.defaultSpatialSpec<IntOffset>()
        val fade = motion.defaultEffectsSpec<Float>()
        return if (!back) {
            (slideInHorizontally(move) { shift } + fadeIn(fade)) togetherWith (slideOutHorizontally(move) { -shift } + fadeOut(fade))
        } else {
            // Tweens, not springs: the back gesture seeks this along its timeline as the finger
            // moves, so the leaving screen must shrink in step with it and only fade in the second
            // half (a quick effects spring had it gone at a third of the swipe, seen on the Pixel 11).
            ContentTransform(
                // The screen beneath shows from the start, peeking around the shrinking one.
                targetContentEnter = slideInHorizontally(tween(BACK_MS, easing = LinearEasing)) { -shift },
                initialContentExit = scaleOut(tween(BACK_MS, easing = LinearEasing), targetScale = 0.9f) +
                    fadeOut(tween(BACK_MS / 2, delayMillis = BACK_MS / 2, easing = LinearEasing)),
                // The leaving screen stays on top, as it does under the finger.
                targetContentZIndex = -1f,
                sizeTransform = SizeTransform(clip = false),
            )
        }
    }
}

/**
 * Ferb's screens, the top of [stack] shown, animated between ([Nav.transform]). Back (button or
 * gesture) pops the stack; the gesture seeks the transition as it's dragged, so the screen beneath
 * peeks (predictive back), and a cancelled swipe settles back. With one screen left, back goes to
 * the system, whose back-to-home animation then runs (`enableOnBackInvokedCallback`). A screen's own
 * BackHandler, composed later, still wins (Tools, with unsaved changes, asks first).
 */
@Composable
fun NavStack(stack: List<String>, onBack: () -> Unit, content: @Composable (String) -> Unit) {
    val state = remember { SeekableTransitionState(stack) }
    val current by rememberUpdatedState(stack)
    val back by rememberUpdatedState(onBack)
    LaunchedEffect(stack) { if (state.currentState != stack) state.animateTo(stack) }
    val scope = rememberCoroutineScope()
    PredictiveBackHandler(enabled = stack.size > 1) { progress ->
        val from = current
        val to = from.dropLast(1)
        try {
            progress.collect { event -> state.seekTo(event.progress, to) }
            // Released: popped, and the LaunchedEffect above finishes the transition.
            back()
        } catch (e: CancellationException) {
            // Swipe cancelled: settle back on the screen (this coroutine is cancelled, so another).
            scope.launch { state.animateTo(from) }
            throw e
        }
    }
    val shift = with(LocalDensity.current) { 30.dp.roundToPx() }
    val motion = MaterialTheme.motionScheme
    rememberTransition(state).AnimatedContent(
        transitionSpec = { Nav.transform(Nav.isBack(initialState, targetState), shift, motion) },
        contentKey = { it },
    ) { shown -> content(shown.last()) }
}

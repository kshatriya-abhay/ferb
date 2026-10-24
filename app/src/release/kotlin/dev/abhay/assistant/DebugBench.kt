package dev.abhay.assistant

import androidx.compose.runtime.Composable

/** The phone bench is in debug builds only (src/debug); release builds have none. */
object DebugBench {
    const val AVAILABLE = false

    @Composable
    fun Screen(onBack: () -> Unit) {}
}

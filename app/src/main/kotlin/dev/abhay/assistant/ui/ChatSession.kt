package dev.abhay.assistant.ui

import dev.abhay.assistant.voice.GeminiWatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The chat, for the process: the full chat and the assistant's sheet over another app show the
 * same conversation, so opening the chat from the sheet carries it on. Screen state, so it lives
 * with the screens, not in the Application.
 */
object ChatSession {
    val state = MutableStateFlow(AssistantViewModel.UiState())
    val exchangeIds = AtomicLong()

    private val following = AtomicBoolean()

    /** When a Gemini Nano download Ferb waited for is ready ([watch]), the chat says so ([GEMINI_READY]); once per process. */
    fun followGemini(watch: Flow<GeminiWatch>) {
        if (!following.compareAndSet(false, true)) return
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            watch.drop(1).filter { it == GeminiWatch.Ready }.collect { state.update { it.copy(notice = GEMINI_READY) } }
        }
    }

    /** The chat's note when a Gemini Nano download Ferb waited for is ready. */
    const val GEMINI_READY = "Gemini Nano is ready"
}

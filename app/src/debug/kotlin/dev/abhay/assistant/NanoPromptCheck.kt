package dev.abhay.assistant

import android.os.SystemClock
import android.util.Log
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.ModelPreference
import com.google.mlkit.genai.prompt.ModelReleaseStage
import com.google.mlkit.genai.prompt.generationConfig
import com.google.mlkit.genai.prompt.modelConfig
import dev.abhay.assistant.voice.NanoChoice

/**
 * Debug builds only (POC): one request through the ML Kit Prompt API, to see Gemini Nano's
 * checkStatus() for FAST and FULL on each release track and how long an answer takes. Nothing is
 * routed to it. Release builds have an empty one (src/release) and no Prompt API.
 */
object NanoPromptCheck {
    const val AVAILABLE = true
    const val TITLE = "Gemini Nano prompt check (debug)"

    private const val PROMPT = "Which one tool fits this request: \"set an alarm for 7 am tomorrow\"? " +
        "Answer with only its name: set_alarm, set_timer, add_reminder, make_call or send_text."

    /** Checks each model and asks the available ones [PROMPT]; one line per model, also logged. */
    suspend fun run(): String {
        val lines = mutableListOf<String>()
        for (stage in listOf(ModelReleaseStage.STABLE, ModelReleaseStage.PREVIEW)) for (preference in listOf(ModelPreference.FAST, ModelPreference.FULL)) {
            val name = "${if (stage == ModelReleaseStage.STABLE) "stable" else "preview"} ${if (preference == ModelPreference.FAST) "FAST" else "FULL"}"
            val model = Generation.getClient(generationConfig { modelConfig = modelConfig { releaseStage = stage; this.preference = preference } })
            val line = try {
                val status = model.checkStatus()
                if (status != FeatureStatus.AVAILABLE) "$name: ${NanoChoice.name(status)}"
                else {
                    val base = runCatching { model.getBaseModelName() }.getOrElse { "?" }
                    val warm = SystemClock.elapsedRealtime()
                    model.warmup()
                    val asked = SystemClock.elapsedRealtime()
                    val answer = model.generateContent(PROMPT).candidates.firstOrNull()?.text?.trim().orEmpty()
                    val done = SystemClock.elapsedRealtime()
                    // Again, warm: the time a second request would take.
                    model.generateContent(PROMPT)
                    val again = SystemClock.elapsedRealtime() - done
                    "$name ($base): warmup ${asked - warm} ms, answer ${done - asked} ms, again $again ms: \"${answer.take(60)}\""
                }
            } catch (e: Exception) {
                "$name: failed: ${e.message ?: e}"
            } finally {
                model.close()
            }
            Log.i("FerbNano", "prompt: $line")
            lines += line
        }
        return lines.joinToString("\n")
    }
}

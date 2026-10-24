package dev.abhay.assistant.bench

import android.os.SystemClock
import android.util.Log
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ModelPreference
import com.google.mlkit.genai.prompt.ModelReleaseStage
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.google.mlkit.genai.prompt.generationConfig
import com.google.mlkit.genai.prompt.modelConfig
import dev.abhay.assistant.AssistantApp
import dev.abhay.assistant.tools.Tools
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Debug builds only: the phone bench, text only. Reads `files/bench/requests.jsonl` (pushed from
 * the laptop by tool-check/phone.py, each line {"id", "request", "tools"}, the tools already routed
 * as the laptop bench routes them) and writes each model's raw answers to `files/bench/<model>.jsonl`,
 * one line per request ({"id", "request", "raw", "ms"}, or "error"), which phone.py scores with the
 * laptop's score.py. A rerun resumes: requests already answered are skipped. One model at a time.
 */
class PhoneBench(private val app: AssistantApp) {
    enum class Model(val file: String) { Needle3("needle3"), GeminiFull("gemini-full"), GeminiFast("gemini-fast") }

    data class Item(val id: String, val request: String, val tools: List<String>)

    val dir = File(app.filesDir, "bench")

    fun items(): List<Item> = File(dir, "requests.jsonl").takeIf { it.exists() }?.readLines().orEmpty().filter { it.isNotBlank() }.map {
        val o = JSONObject(it)
        val tools = o.getJSONArray("tools")
        Item(o.getString("id"), o.getString("request"), (0 until tools.length()).map(tools::getString))
    }

    private fun out(model: Model) = File(dir, "${model.file}.jsonl")

    /** The ids [model] has answered already. */
    fun done(model: Model): Set<String> = out(model).takeIf { it.exists() }?.readLines().orEmpty()
        .filter { it.isNotBlank() }.mapNotNull { runCatching { JSONObject(it).getString("id") }.getOrNull() }.toSet()

    /** Answers what's left (at most [limit]), calling [progress] with (done, total) after each; stops on a blocking error. */
    suspend fun run(model: Model, limit: Int = Int.MAX_VALUE, progress: (Int, Int, String?) -> Unit = { _, _, _ -> }): String = withContext(Dispatchers.Default) {
        val items = items()
        require(items.isNotEmpty()) { "no requests: push files/bench/requests.jsonl first (tool-check/phone.py push)" }
        val answered = done(model).toMutableSet()
        val todo = items.filter { it.id !in answered }.take(limit)
        Log.i(TAG, "${model.file}: ${todo.size} to go of ${items.size}")
        val gemini = if (model == Model.Needle3) null else gemini(model)
        try {
            for (item in todo) {
                val line = JSONObject().put("id", item.id).put("request", item.request)
                val started = SystemClock.elapsedRealtime()
                try {
                    if (gemini == null) line.put("raw", needle(item))
                    else line.put("raw", ask(gemini, item, line))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    line.put("error", e.message ?: e.toString())
                    (e as? GenAiException)?.let { line.put("code", it.errorCode) }
                }
                line.put("ms", SystemClock.elapsedRealtime() - started)
                out(model).appendText(line.toString() + "\n")
                answered += item.id
                progress(answered.size, items.size, line.optString("error").ifEmpty { null })
                // Quota or background use: every request after would fail too.
                val code = line.optInt("code", -1)
                if (code == GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED || code == GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED) {
                    // Not an answer: kept out of the file, so a rerun asks it again.
                    val kept = out(model).readLines().dropLast(1)
                    out(model).writeText(kept.joinToString("\n", postfix = if (kept.isEmpty()) "" else "\n"))
                    return@withContext "stopped: ${line.optString("error")} (code $code)"
                }
            }
        } finally {
            gemini?.close()
        }
        "${model.file}: ${answered.size} of ${items.size} answered"
    }

    /** Needle 3 as the app asks it: the routed tools, through the app's engine. */
    private suspend fun needle(item: Item): String {
        val tools = item.tools.mapNotNull(Tools::byName)
        return app.engine.complete(item.request, tools.map { it.name }, Tools.json(tools))
    }

    private suspend fun gemini(model: Model): GenerativeModel {
        val preference = if (model == Model.GeminiFast) ModelPreference.FAST else ModelPreference.FULL
        val client = Generation.getClient(generationConfig { modelConfig = modelConfig { releaseStage = ModelReleaseStage.STABLE; this.preference = preference } })
        val status = client.checkStatus()
        check(status == FeatureStatus.AVAILABLE) { "Gemini Nano ${model.file} isn't available (checkStatus $status)" }
        client.warmup()
        tokenLimit = runCatching { client.getTokenLimit() }.getOrNull()
        Log.i(TAG, "${model.file}: ${client.getBaseModelName()}, token limit $tokenLimit")
        return client
    }

    private var tokenLimit: Int? = null

    /**
     * Computer mode's schema call (timed, t4) as the system instruction, the request as typed.
     * The API can't enforce the answer's JSON schema, so it goes in the prompt as text, unless
     * that makes the request too long for the model (noted as "schema": false).
     */
    private suspend fun ask(model: GenerativeModel, item: Item, line: JSONObject): String {
        val tools = JSONArray(Tools.json(item.tools.mapNotNull(Tools::byName)))
        val base = SchemaCall.prompt(tools, rewrite = false, timed = true, version = "t4") + "\nNow: $NOW."
        val schema = JSONObject(SchemaCall.format(tools, rewrite = false, timed = true, version = "t4"))
            .getJSONObject("json_schema").getJSONObject("schema").toString()
        val withSchema = "$base\n\nThe answer's JSON schema:\n$schema"
        fun request(system: String) = generateContentRequest(SystemInstruction(system), TextPart(item.request)) {
            temperature = 0f
            topK = 1
            maxOutputTokens = 600
        }
        var system = withSchema
        val limit = tokenLimit
        if (limit != null) {
            val tokens = runCatching { model.countTokens(request(withSchema)).totalTokens }.getOrNull()
            if (tokens != null) line.put("tokens", tokens)
            if (tokens != null && tokens > limit - 600) system = base
        }
        line.put("schema", system === withSchema)
        var attempt = 0
        while (true) {
            try {
                return model.generateContent(request(system)).candidates.firstOrNull()?.text.orEmpty()
            } catch (e: GenAiException) {
                // BUSY: AICore is serving another app; wait and try again, three times.
                if (e.errorCode != GenAiException.ErrorCode.BUSY || ++attempt > 3) throw e
                delay(2_000L * attempt)
            }
        }
    }

    companion object {
        const val TAG = "FerbBench"
        /** schema.py's NOW, so dates read as in the laptop's schema-call runs. */
        const val NOW = "2026-10-16T09:00 (Friday)"
    }
}

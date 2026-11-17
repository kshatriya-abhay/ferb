package dev.abhay.assistant.bench

import android.os.SystemClock
import android.util.Log
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ModelPreference
import com.google.mlkit.genai.prompt.ModelReleaseStage
import com.google.mlkit.genai.prompt.generationConfig
import com.google.mlkit.genai.prompt.modelConfig
import dev.abhay.assistant.AssistantApp
import dev.abhay.assistant.tools.Tools
import dev.abhay.assistant.voice.GeminiRewrite
import dev.abhay.assistant.voice.NanoEars
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Debug builds only: the phone bench, text only: Needle 3, and Gemini Nano's rewrite before it. Reads `files/bench/requests.jsonl` (pushed from
 * the laptop by tool-check/phone.py, each line {"id", "request", "tools"}, the tools already routed
 * as the laptop bench routes them) and writes each model's raw answers to `files/bench/<model>.jsonl`,
 * one line per request ({"id", "request", "raw", "ms"}, or "error"), which phone.py scores with the
 * laptop's score.py. A rerun resumes: requests already answered are skipped. One model at a time.
 */
class PhoneBench(private val app: AssistantApp) {
    /**
     * Needle 3, and Gemini Nano's rewrite before it with the prompt the app ships
     * ([GeminiRewrite.PROMPT], FULL; FAST gave the same answers on this phone), whose output
     * tool-check/phone.py routes and gives Needle 3 (run with [input] set to its file). The
     * rewrite's file is named by the prompt's version, so a new prompt starts afresh.
     */
    enum class Model(
        val file: String,
        val input: String = REQUESTS,
        /** Gemini Nano's system prompt for an item; null for Needle 3. */
        val prompt: ((Item) -> String)? = null,
        val maxTokens: Int = GeminiRewrite.MAX_TOKENS,
        /** Whether the answer is a rewritten command (else kept raw, as the slots' JSON is). */
        val command: Boolean = true,
    ) {
        Needle3("needle3"),
        Rewrite("rewrites-${GeminiRewrite.VERSION}-gemini-full", prompt = { GeminiRewrite.PROMPT });
        // The date rounds' passes (2026-10-24 to 10-29: datetime-v2, the blind sets) went with the
        // rounds (review P2-183): a one-tap rerun of a spent blind set invites tuning on it.
    }

    /** [now]: the clock the request is said at, for the date lines. */
    data class Item(val id: String, val request: String, val tools: List<String>, val now: java.time.LocalDateTime? = null)

    val dir = File(app.filesDir, "bench")

    fun items(input: String = REQUESTS): List<Item> = File(dir, input).takeIf { it.exists() }?.readLines().orEmpty().filter { it.isNotBlank() }.map {
        val o = JSONObject(it)
        val tools = o.getJSONArray("tools")
        Item(o.getString("id"), o.getString("request"), (0 until tools.length()).map(tools::getString),
            o.optString("now").takeIf { it.isNotEmpty() }?.let(java.time.LocalDateTime::parse))
    }

    private fun out(model: Model, output: String? = null) = File(dir, "${output ?: model.file}.jsonl")

    /**
     * The ids [model] has answered already (into [output], if not its own file), for [items] as
     * they are now: an answer counts only for the same request with the same tools, and any other
     * is dropped from the file. phone.py's ids are positions, so after a set edit or a routing
     * change an id names another line, and its old answer was scored as the new one (review P2-173).
     */
    fun done(model: Model, items: List<Item> = items(model.input), output: String? = null): Set<String> =
        current(model, items, output).map { JSONObject(it).getString("id") }.toSet()

    /** [done]'s answer lines, as in the file. */
    private fun current(model: Model, items: List<Item>, output: String?): List<String> {
        val file = out(model, output).takeIf { it.exists() } ?: return emptyList()
        val now = items.associateBy { it.id }
        return file.readLines().filter { it.isNotBlank() }.filter { l ->
            runCatching {
                val o = JSONObject(l)
                val item = now[o.getString("id")]
                val tools = o.optJSONArray("tools")?.let { a -> (0 until a.length()).map(a::getString) }
                item != null && o.optString("request") == item.request && tools == item.tools
            }.getOrDefault(false)
        }
    }

    /** Drops [model]'s answers that aren't for [items] as they are now ([done]), before a run adds to them. */
    private fun prune(model: Model, items: List<Item>, output: String?) {
        val file = out(model, output).takeIf { it.exists() } ?: return
        val all = file.readLines().count { it.isNotBlank() }
        val kept = current(model, items, output)
        if (kept.size != all) {
            Log.i(TAG, "${file.name}: ${all - kept.size} answers for other lines or tools dropped")
            file.writeText(kept.joinToString("") { it + "\n" })
        }
    }

    /** Answers what's left (at most [limit]), calling [progress] with (done, total) after each; stops on a blocking error. */
    suspend fun run(model: Model, limit: Int = Int.MAX_VALUE, input: String = model.input, output: String? = null,
                    progress: (Int, Int, String?) -> Unit = { _, _, _ -> }): String = withContext(Dispatchers.Default) {
        val items = items(input)
        require(items.isNotEmpty()) { "no requests: push files/bench/$input first (tool-check/phone.py)" }
        fun out(model: Model) = out(model, output)
        prune(model, items, output)
        val answered = done(model, items, output).toMutableSet()
        val todo = items.filter { it.id !in answered }.take(limit)
        Log.i(TAG, "${model.file}: ${todo.size} to go of ${items.size}")
        val gemini = if (model == Model.Needle3) null else gemini(model)
        try {
            for (item in todo) {
                val line = JSONObject().put("id", item.id).put("request", item.request).put("tools", org.json.JSONArray(item.tools))
                val started = SystemClock.elapsedRealtime()
                try {
                    if (gemini == null) line.put("raw", needle(item))
                    else {
                        val prompt = model.prompt!!(item)
                        val candidate = paced(line) { gemini.generateContent(NanoEars.rewriteRequest(item.request, prompt, model.maxTokens)) }.candidates.firstOrNull()
                        val raw = candidate?.text.orEmpty()
                        val cut = candidate?.finishReason == com.google.mlkit.genai.prompt.Candidate.FinishReason.MAX_TOKENS
                        line.put("raw", raw)
                        if (cut) line.put("cut", true)
                        // A cut-off command fails as in the app: the request goes on as typed.
                        if (model.command) line.put("command", GeminiRewrite.parse(item.request, raw, cut).command ?: JSONObject.NULL)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    line.put("error", e.message ?: e.toString())
                    (e as? GenAiException)?.let { line.put("code", it.errorCode) }
                }
                // The model's own time: waits for AICore's quota (paced) aren't latency.
                line.put("ms", SystemClock.elapsedRealtime() - started - line.optLong("waited_ms", 0))
                out(model).appendText(line.toString() + "\n")
                answered += item.id
                progress(answered.size, items.size, line.optString("error").ifEmpty { null })
                // Quota or background use: every request after would fail too.
                val code = line.optInt("code", -1)
                if (code == GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED || code == GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED ||
                    code == GenAiException.ErrorCode.BUSY) {
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
        val client = Generation.getClient(generationConfig { modelConfig = modelConfig { releaseStage = ModelReleaseStage.STABLE; preference = ModelPreference.FULL } })
        val status = client.checkStatus()
        check(status == FeatureStatus.AVAILABLE) { "Gemini Nano ${model.file} isn't available (checkStatus $status)" }
        client.warmup()
        tokenLimit = runCatching { client.getTokenLimit() }.getOrNull()
        Log.i(TAG, "${model.file}: ${client.getBaseModelName()}, token limit $tokenLimit")
        return client
    }

    private var tokenLimit: Int? = null
    private var lastCall = 0L

    /**
     * [call] at AICore's pace: at least [GAP_MS] after the last request, and on BUSY ("out of
     * usage quota, try again later": the rewrite pass met it after 421 requests at about 4 a
     * second, 2026-10-24) waits 15, 30, 60, 60 then 60 s before trying again, then gives up (the
     * pass stops, so a rerun asks it again). The waits go in "waited_ms", out of the latency.
     */
    private suspend fun <T> paced(line: JSONObject, call: suspend () -> T): T {
        var waited = 0L
        for (wait in listOf(0L, 15_000L, 30_000L, 60_000L, 60_000L, 60_000L)) {
            val gap = (lastCall + GAP_MS - SystemClock.elapsedRealtime()).coerceAtLeast(0) + wait
            if (gap > 0) { delay(gap); waited += gap }
            line.put("waited_ms", waited)
            try {
                return call().also { lastCall = SystemClock.elapsedRealtime() }
            } catch (e: GenAiException) {
                lastCall = SystemClock.elapsedRealtime()
                if (e.errorCode != GenAiException.ErrorCode.BUSY || wait == 60_000L && waited >= 225_000L) throw e
                Log.w(TAG, "BUSY; waiting (${waited / 1000} s so far)")
            }
        }
        error("unreachable")
    }

    companion object {
        const val TAG = "FerbBench"
        const val REQUESTS = "requests.jsonl"
        /** At most about 2 requests a second to AICore. */
        const val GAP_MS = 500L
    }
}

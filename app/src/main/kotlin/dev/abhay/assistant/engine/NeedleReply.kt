package dev.abhay.assistant.engine

import org.json.JSONArray
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import org.json.JSONObject

/**
 * Ferb's reading of a call's time ([dev.abhay.assistant.tools.DateGate]): the only time a timed tool
 * reads, never the model's own value. [at]: an event's or reminder's date and time; [day]: the
 * agenda's or an all-day event's date; [repeat]: a reminder's repeat; [lengthMinutes]: an
 * event's length from a span said ("6 to 8 pm"); [wanted]: for an alarm on another day than its
 * next ring, when it was wanted (a reminder could do that); [ask]: why to ask first, or null to run;
 * [clock]: an alarm's time of day; [seconds]: a timer's length.
 */
data class FerbTime(
    val at: LocalDateTime? = null,
    val day: LocalDate? = null,
    val repeat: dev.abhay.assistant.reminders.Repeat? = null,
    val lengthMinutes: Int? = null,
    val wanted: LocalDateTime? = null,
    val ask: String? = null,
    val clock: LocalTime? = null,
    val seconds: Int? = null,
)

/**
 * One call Needle 3 asked for: a tool name and its arguments as the engine returned them, and
 * [time], Ferb's own reading of its time ([dev.abhay.assistant.tools.DateGate]) when it's a timed call.
 */
data class ToolCall(val name: String, val arguments: Map<String, Any?>, val time: FerbTime? = null) {
    /** `name(key = value, …)` with strings quoted, for display. */
    fun signature(): String = arguments.entries.joinToString(prefix = "$name(", postfix = ")") { (key, value) ->
        "$key = " + if (value is String) "\"$value\"" else value.toString()
    }
}

/** Needle 3's reply to one request (the engine's JSON from `needle_complete`). */
data class NeedleReply(
    val calls: List<ToolCall>,
    /** Calls the engine held back: below its 0.1 floor, ungrounded arguments, or a negated request. */
    val suppressed: List<ToolCall>,
    val reasoning: String,
    /** 0..1, calibrated for the base model; null when the engine doesn't give one. */
    val confidence: Double?,
    /** Arguments the engine found no evidence for in the request, as "tool.argument". */
    val ungrounded: List<String>,
    val negation: Boolean,
    val prefillTps: Double,
    val decodeTps: Double,
    val peakRamMb: Double,
    /** Set when the engine reports a failure instead of an answer; for Ferb Link's, a few words that fit in the chat. */
    val error: String?,
) {

    companion object {
        fun parse(json: String): NeedleReply = parse(JSONObject(json))

        /** [parse] on the engine's JSON already read. */
        internal fun parse(o: JSONObject): NeedleReply {
            val validation = o.optJSONObject("validation")
            return NeedleReply(
                calls = calls(o.optJSONArray("function_calls")),
                suppressed = calls(o.optJSONArray("suppressed_calls")),
                reasoning = o.text("reasoning").orEmpty(),
                confidence = if (o.isNull("confidence")) null else o.optDouble("confidence"),
                ungrounded = validation?.optJSONArray("ungrounded")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
                negation = validation?.optBoolean("negation") ?: false,
                prefillTps = o.optDouble("prefill_tps", 0.0),
                decodeTps = o.optDouble("decode_tps", 0.0),
                peakRamMb = o.optDouble("peak_ram_mb", 0.0),
                error = if (o.optBoolean("success", true)) null else o.text("error") ?: "Engine error",
            )
        }

        private fun calls(array: JSONArray?): List<ToolCall> = (0 until (array?.length() ?: 0)).map { i ->
            val call = array!!.getJSONObject(i)
            val args = call.optJSONObject("arguments") ?: JSONObject()
            ToolCall(call.getString("name"), args.keys().asSequence().associateWith { args.get(it).takeUnless { v -> v == JSONObject.NULL } })
        }
    }
}

/**
 * What the assistant does with a reply. Cactus recommends running at 0.7; the assistant uses a bar
 * per tool ([dev.abhay.assistant.tools.ToolConfig.runFrom]), and a reply runs when it clears the highest
 * bar among the tools it calls.
 */
enum class Decision {
    /** Confident enough: run the calls straight away. */
    Run,
    /** Below the bar (and above the engine's 0.1 floor): show the calls and ask before running. */
    Confirm,
    /** No call: no tool fits, or the engine withheld everything. */
    Refuse;

    companion object {
        const val RUN_AT = 0.7

        fun of(reply: NeedleReply, runAt: Double = RUN_AT): Decision = when {
            reply.calls.isEmpty() -> Refuse
            (reply.confidence ?: 0.0) >= runAt -> Run
            else -> Confirm
        }
    }
}

/**
 * [key]'s string, or null when it's missing, JSON null or empty: the one way the engine's JSON is read
 * as text. org.json's optString gives "" for a JSON null on the JVM but "null" on Android, so a failure
 * the engine reports with "error": null read "Needle 3: null" on the phone (review P2-92).
 */
internal fun JSONObject.text(key: String): String? = if (isNull(key)) null else optString(key).ifEmpty { null }

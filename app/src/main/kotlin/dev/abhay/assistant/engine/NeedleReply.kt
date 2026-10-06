package dev.abhay.assistant.engine

import org.json.JSONArray
import org.json.JSONObject

/** One call Needle 3 asked for: a tool name and its arguments as the engine returned them. */
data class ToolCall(val name: String, val arguments: Map<String, Any?>) {
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
    /** Set when the engine reports a failure instead of an answer. */
    val error: String?,
) {
    companion object {
        fun parse(json: String): NeedleReply {
            val o = JSONObject(json)
            val validation = o.optJSONObject("validation")
            return NeedleReply(
                calls = calls(o.optJSONArray("function_calls")),
                suppressed = calls(o.optJSONArray("suppressed_calls")),
                reasoning = o.optString("reasoning"),
                confidence = if (o.isNull("confidence")) null else o.optDouble("confidence"),
                ungrounded = validation?.optJSONArray("ungrounded")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
                negation = validation?.optBoolean("negation") ?: false,
                prefillTps = o.optDouble("prefill_tps", 0.0),
                decodeTps = o.optDouble("decode_tps", 0.0),
                peakRamMb = o.optDouble("peak_ram_mb", 0.0),
                error = if (o.optBoolean("success", true)) null else o.optString("error").ifEmpty { "Engine error" },
            )
        }

        private fun calls(array: JSONArray?): List<ToolCall> = (0 until (array?.length() ?: 0)).map { i ->
            val call = array!!.getJSONObject(i)
            val args = call.optJSONObject("arguments") ?: JSONObject()
            ToolCall(call.getString("name"), args.keys().asSequence().associateWith { args.get(it).takeUnless { v -> v == JSONObject.NULL } })
        }
    }
}

/** What the assistant does with a reply, using the thresholds Cactus recommends. */
enum class Decision {
    /** Confidence ≥ 0.7: run the calls straight away. */
    Run,
    /** Confidence between the engine's 0.1 floor and 0.7: show the calls and ask before running. */
    Confirm,
    /** No call: no tool fits, or the engine withheld everything. */
    Refuse;

    companion object {
        const val RUN_AT = 0.7

        fun of(reply: NeedleReply): Decision = when {
            reply.calls.isEmpty() -> Refuse
            (reply.confidence ?: 0.0) >= RUN_AT -> Run
            else -> Confirm
        }
    }
}

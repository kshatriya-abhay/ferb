package dev.abhay.assistant.engine

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Gemini Nano's schema-call answer ({"calls": [{"tool", "arguments", "when"}]}), read the way Ferb
 * would act on it. Without an enforced schema, 205 of 485 FAST answers on the phone bench
 * (2026-10-24) weren't valid JSON: mostly one call written whole and then repeated until the
 * token cap, or a brace left out ({"tool": "set_timer", "arguments": {...}]}). So, in order:
 * the whole answer when it parses (anything after it ignored); else the first complete call
 * object; else the first "tool" name with the arguments object after it. Anything else is
 * unreadable. Calls to a tool that wasn't offered are dropped. tool-check/phone.py reads answers
 * by the same rules (checked against this on the bench's answers: SchemaCallReplyTest).
 */
object SchemaCallReply {
    data class Call(val tool: String, val arguments: JSONObject, val `when`: JSONObject?)

    /** How an answer was read. */
    enum class How { Whole, FirstCall, ToolArguments, Unreadable }

    data class Reply(val calls: List<Call>, val how: How)

    private val toolArguments = Regex("\"tool\"\\s*:\\s*\"(\\w+)\"\\s*,\\s*\"arguments\"\\s*:\\s*")
    private val whenAfter = Regex("^\\s*,\\s*\"when\"\\s*:\\s*")

    fun parse(raw: String, offered: Set<String>): Reply {
        fun offeredOnly(calls: List<Call>, how: How) = Reply(calls.filter { it.tool in offered }, how)
        val start = raw.indexOf('{')
        if (start < 0) return Reply(emptyList(), How.Unreadable)
        // The whole answer: its first JSON value, with what follows ignored.
        (value(raw, start) as? JSONObject)?.let { answer ->
            val calls = answer.opt("calls") as? JSONArray
            if (calls != null) return offeredOnly((0 until calls.length()).mapNotNull { call(calls.opt(it)) }, How.Whole)
        }
        // The first complete call object.
        var i = raw.indexOf('{', start + 1)
        while (i >= 0) {
            call(value(raw, i))?.let { return offeredOnly(listOf(it), How.FirstCall) }
            i = raw.indexOf('{', i + 1)
        }
        // A "tool" with the arguments object after it.
        for (m in toolArguments.findAll(raw)) {
            val arguments = valueAt(raw, m.range.last + 1) ?: continue
            val (args, end) = arguments
            if (args !is JSONObject) continue
            val w = whenAfter.find(raw.substring(end))?.let { valueAt(raw, end + it.range.last + 1)?.first as? JSONObject }
            return offeredOnly(listOf(Call(m.groupValues[1], args, w)), How.ToolArguments)
        }
        return Reply(emptyList(), How.Unreadable)
    }

    /** A call object: a tool name and an arguments object ("when" an object, or none). */
    private fun call(value: Any?): Call? {
        val o = value as? JSONObject ?: return null
        val tool = o.opt("tool") as? String ?: return null
        val arguments = o.opt("arguments") as? JSONObject ?: return null
        return Call(tool, arguments, o.opt("when") as? JSONObject)
    }

    private fun value(raw: String, at: Int): Any? = valueAt(raw, at)?.first

    /** The strict JSON value starting at [at] (spaces skipped), and where it ends; null if there isn't one. */
    private fun valueAt(raw: String, at: Int): Pair<Any, Int>? {
        var i = at
        while (i < raw.length && raw[i].isWhitespace()) i++
        if (i >= raw.length) return null
        val end = jsonEnd(raw, i) ?: return null
        return try {
            JSONTokener(raw.substring(i, end)).nextValue() to end
        } catch (e: JSONException) {
            null
        }
    }

    /**
     * Where the strict JSON value at [i] ends: an object or array to its matching bracket (outside
     * strings), or a string, number, true, false or null. org.json alone would also take unquoted
     * words; this keeps it to JSON, as Python's json reads it.
     */
    private fun jsonEnd(raw: String, i: Int): Int? = when (raw[i]) {
        '{', '[' -> {
            val stack = ArrayDeque<Char>()
            var j = i
            var inString = false
            var result: Int? = null
            while (j < raw.length) {
                val c = raw[j]
                if (inString) {
                    if (c == '\\') j++ else if (c == '"') inString = false
                } else when (c) {
                    '"' -> inString = true
                    '{', '[' -> stack.addLast(c)
                    '}', ']' -> {
                        val open = stack.removeLastOrNull() ?: return null
                        if ((open == '{') != (c == '}')) return null
                        if (stack.isEmpty()) { result = j + 1; break }
                    }
                }
                j++
            }
            result?.takeIf { strict(raw.substring(i, it)) }
        }
        '"' -> {
            var j = i + 1
            while (j < raw.length && raw[j] != '"') { if (raw[j] == '\\') j++; j++ }
            if (j < raw.length) j + 1 else null
        }
        else -> Regex("^(-?\\d+(\\.\\d+)?([eE][+-]?\\d+)?|true|false|null)").find(raw.substring(i))?.let { i + it.value.length }
    }

    /** Whether [text] (balanced brackets) is strict JSON: every key quoted, every bare word true, false or null, no trailing commas. */
    private fun strict(text: String): Boolean {
        var j = 0
        var inString = false
        var last = ' ' // the last character outside strings and spaces
        while (j < text.length) {
            val c = text[j]
            if (inString) {
                if (c == '\\') j++ else if (c == '"') { inString = false; last = '"' }
            } else when {
                c == '"' -> { if (last == '"' ) return false; inString = true }
                c.isWhitespace() -> {}
                c == '}' || c == ']' -> { if (last == ',') return false; last = c }
                c in "{[,:" -> last = c
                else -> {
                    val word = Regex("^(-?\\d+(\\.\\d+)?([eE][+-]?\\d+)?|true|false|null)").find(text.substring(j)) ?: return false
                    if (last !in ":[,") return false
                    j += word.value.length - 1
                    last = '0'
                }
            }
            j++
        }
        return !inString
    }
}

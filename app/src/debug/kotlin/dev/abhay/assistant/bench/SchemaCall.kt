package dev.abhay.assistant.bench

import org.json.JSONArray
import org.json.JSONObject

/*
 * Debug builds only (the phone bench): Computer mode's schema call, copied verbatim from Ferb Link
 * (companion SchemaCall.prompt/format and TimeFormat's t4 shapes, ferb-link 50f3f37), timed t4 only.
 * Gemini Nano's Prompt API can't enforce a JSON schema, so the bench puts format()'s schema in the
 * prompt as text instead ([PhoneBench]). Keep in step with Ferb Link if the bench is rerun.
 */
object SchemaCall {
    fun prompt(tools: JSONArray, rewrite: Boolean, timed: Boolean = false, version: String = "t3") = buildString {
        append("You are Ferb, an assistant on an Android phone. Reply with JSON only: {\"calls\": [...]")
        if (rewrite) append(", \"commands\": [...]")
        append("}.\n")
        append("- \"calls\": one call for each thing the user asks the phone to do, in order: {\"tool\": its name, \"arguments\": its arguments in the user's words, \"when\": its time, or null for a tool that takes none}. ")
        append("An empty list when no tool fits, or when the user isn't asking for an action (a question about the world, chat, a statement about something, the past or themselves).\n")
        if (rewrite) {
            append("- \"commands\": when the request is Hinglish, vague or needs more than the tools' words, leave \"calls\" empty and instead write each thing asked as a plain English command (\"Set an alarm for 7:15 am tomorrow\"); otherwise [].\n")
        }
        append("Never add a name, place, number, time or date the request doesn't have.\n")
        // timed (option c): "when" only in the timed tools' shapes, trimmed; otherwise every call has one.
        require(timed && version == "t4") { "the phone bench copies only timed t4" }
        append(TimeFormat.PROMPT_T4)
        append("\n\nThe tools:\n")
        for (i in 0 until tools.length()) append(tools.getJSONObject(i).toString()).append('\n')
    }

    fun format(tools: JSONArray, rewrite: Boolean, timed: Boolean = false, version: String = "t3"): String {
        val calls = (0 until tools.length()).joinToString(", ") { i ->
            val tool = tools.getJSONObject(i)
            val name = tool.getString("name")
            val parameters = tool.optJSONObject("parameters") ?: JSONObject().put("type", "object").put("properties", JSONObject())
            val shape = TimeFormat.forTool(name)
            // A tool without a time still ends with "when": null. Ending at "arguments" let the model
            // write the answer's closing JSON inside a string argument ("Calculator}}]}```").
            if (shape == null) """{"type": "object", "properties": {"tool": {"enum": [${JSONObject.quote(name)}]}, "arguments": $parameters, "when": {"type": "null"}}, "required": ["tool", "arguments", "when"]}"""
            else """{"type": "object", "properties": {"tool": {"enum": [${JSONObject.quote(name)}]}, "arguments": $parameters, "when": $shape}, "required": ["tool", "arguments", "when"]}"""
        }
        val items = if (tools.length() == 0) """{"type": "object"}""" else """{"anyOf": [$calls]}"""
        val properties = """"calls": {"type": "array", "items": $items}""" + if (rewrite) """, "commands": {"type": "array", "items": {"type": "string"}}""" else ""
        val required = if (rewrite) """["calls", "commands"]""" else """["calls"]"""
        return """{"type": "json_schema", "json_schema": {"schema": {"type": "object", "properties": {$properties}, "required": $required}}}"""
    }
}

object TimeFormat {
    const val INT = """{"type": ["integer", "null"]}"""
    const val WEEKDAY = """{"enum": ["monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"]}"""
    const val TIME = """{"anyOf": [{"type": "null"}, {"type": "object", "properties": {"hour": $INT, "minute": $INT, "meridiem": {"enum": ["am", "pm", null]}, "part": {"enum": ["morning", "afternoon", "evening", "night", null]}}, "required": ["hour", "minute", "meridiem", "part"]}]}"""
    const val DAY = """{"anyOf": [{"type": "null"}, {"type": "object", "properties": {"relative": {"enum": [0, 1, 2]}}, "required": ["relative"]}, """ +
        """{"type": "object", "properties": {"weekday": $WEEKDAY, "which": {"enum": ["this", "next", null]}}, "required": ["weekday", "which"]}, """ +
        """{"type": "object", "properties": {"day": {"type": "integer"}, "month": $INT, "year": $INT}, "required": ["day", "month", "year"]}, """ +
        """{"type": "object", "properties": {"span": {"enum": ["weekend"]}}, "required": ["span"]}]}"""
    const val LENGTH = """{"type": "object", "properties": {"days": {"type": "integer"}, "hours": {"type": "integer"}, "minutes": {"type": "integer"}, "seconds": {"type": "integer"}}, "required": ["days", "hours", "minutes", "seconds"]}"""
    const val CLOCK = """{"type": "object", "properties": {"hour": {"type": "integer"}, "minute": {"type": "integer"}, "meridiem": {"enum": ["am", "pm", null]}}, "required": ["hour", "minute", "meridiem"]}"""
    const val ALARM = """{"type": "object", "properties": {"kind": {"enum": ["at"]}, "day": $DAY, "time": $CLOCK}, "required": ["kind", "day", "time"]}"""
    const val TIMER = """{"type": "object", "properties": {"kind": {"enum": ["duration"]}, "length": $LENGTH}, "required": ["kind", "length"]}"""
    const val EVENT = """{"type": "object", "properties": {"kind": {"enum": ["at"]}, "day": $DAY, "time": $TIME}, "required": ["kind", "day", "time"]}"""
    const val REMINDER = """{"anyOf": [$EVENT, """ +
        """{"type": "object", "properties": {"kind": {"enum": ["in"]}, "length": $LENGTH}, "required": ["kind", "length"]}, """ +
        """{"type": "object", "properties": {"kind": {"enum": ["every"]}, "period": {"enum": ["day", "week", "weekdays", "month", "year"]}, "on": {"type": "array", "items": $WEEKDAY}, "time": $TIME}, "required": ["kind", "period", "on", "time"]}]}"""
    const val IN = """{"type": "object", "properties": {"kind": {"enum": ["in"]}, "length": $LENGTH}, "required": ["kind", "length"]}"""
    const val ALARM_T4 = """{"anyOf": [$ALARM, $IN]}"""
    const val PROMPT_T4 = """For an alarm, timer, reminder or event, "when" is the time exactly as said, as structure. You never calculate: no clock time from "in an hour", no date from a weekday, no month or year that wasn't said. Anything not said is null.
- "in", "after", "mein" or "baad" with a length ("in 20 minutes", "aadhe ghante mein", "2 ghante baad") is always {"kind": "in", "length": ...}, for alarms too. Never turn it into a clock time.
- "every", "har", "roz", "daily", "each" is always {"kind": "every", ...}: "roz" or "daily" is period day; "every Monday" or "har somvar" is period week with on ["monday"]; "weekdays" is period weekdays.
- day: {"relative": 0} only for today, aaj or tonight; {"relative": 1} only for tomorrow or kal; {"relative": 2} only for the day after tomorrow or parso. A named weekday stays a weekday: {"weekday": ..., "which": "this", "next" or null}. A date stays as said: {"day": n, "month": m or null if no month is said, "year": null unless a year is said}. Null when no day is said: never add today or this weekday.
- time: hour and minute as said. meridiem only when am or pm is said, or from subah (am), dopahar, shaam or raat (pm); otherwise null. part only when a part of the day is named; never for a bare clock time.
- length: days, hours, minutes, seconds as said, 0 for the rest.
- Hindi numbers: ek 1, do 2, teen 3, chaar 4, paanch 5, chhe 6, saat 7, aath 8, nau 9, das 10, gyarah 11, barah 12, pandrah 15, bees 20, pachchees 25, tees 30, paintalees 45. sava is a quarter more (sava aath 8:15), saade a half more (saade saat 7:30), paune a quarter less (paune aath 7:45, paune ghanta 45 minutes), dedh one and a half, dhai two and a half, aadha half. With baje it is always a clock time, kind "at", never "in": "paune aath baje" is hour 7, minute 45; "saade paanch baje" is hour 5, minute 30.
Examples: "on the 27th of March at 4" → {"kind": "at", "day": {"day": 27, "month": 3, "year": null}, "time": {"hour": 4, "minute": 0, "meridiem": null, "part": null}}; "in 45 minutes" → {"kind": "in", "length": {"days": 0, "hours": 0, "minutes": 45, "seconds": 0}}; "har mangalvar shaam 9 baje" → {"kind": "every", "period": "week", "on": ["tuesday"], "time": {"hour": 9, "minute": 0, "meridiem": "pm", "part": "evening"}}; "on Wednesday" → {"kind": "at", "day": {"weekday": "wednesday", "which": null}, "time": null}."""

    fun forTool(name: String): String? = when (name) {
        "set_alarm" -> ALARM_T4
        "set_timer" -> TIMER
        "add_event" -> EVENT
        "add_reminder" -> REMINDER
        else -> null
    }
}

package dev.abhay.assistant.engine

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.abhay.assistant.engine.SchemaCallReply.How
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

/**
 * Gemini Nano's schema-call answers read as Ferb would act on them. The bench's 485 FAST answers
 * from the Pixel (2026-10-24; 205 of them not valid JSON) with tool-check/phone.py's reading of
 * each: the two must agree, so the bench scores what Ferb would run.
 */
class SchemaCallReplyTest {
    private fun plain(v: Any?): Any? = when (v) {
        is JSONObject -> v.keys().asSequence().associateWith { plain(v.get(it)) }
        is JSONArray -> (0 until v.length()).map { plain(v.get(it)) }
        JSONObject.NULL -> null
        is Number -> v.toDouble()
        else -> v
    }

    private fun parse(raw: String, vararg offered: String) = SchemaCallReply.parse(raw, offered.toSet())

    @Test
    fun theBenchsAnswersAreReadAsPhonePyReadsThem() {
        val lines = javaClass.classLoader!!.getResource("gemini-fast-answers.jsonl").readText().lines().filter { it.isNotBlank() }
        assertThat(lines).hasSize(485)
        for (line in lines) {
            val o = JSONObject(line)
            val offered = o.getJSONArray("offered").let { a -> (0 until a.length()).map(a::getString).toSet() }
            val got = SchemaCallReply.parse(o.getString("raw"), offered)
            val want = o.getJSONArray("calls")
            val raw = o.getString("raw").take(120)
            assertWithMessage(raw).that(got.how.name).isEqualTo(o.getString("how"))
            assertWithMessage(raw).that(got.calls.map { mapOf("tool" to it.tool, "arguments" to plain(it.arguments), "when" to plain(it.`when`)) })
                .isEqualTo(plain(want))
        }
    }

    @Test
    fun aWholeAnswerGivesEveryOfferedCall() {
        val got = parse("""{"calls": [{"tool": "set_flashlight", "arguments": {"on": true}, "when": null}, {"tool": "set_volume", "arguments": {"percent": 20}, "when": null}]}""",
            "set_flashlight", "set_volume")
        assertThat(got.how).isEqualTo(How.Whole)
        assertThat(got.calls.map { it.tool }).containsExactly("set_flashlight", "set_volume").inOrder()
        assertThat(parse("""{"calls": []}""", "set_alarm")).isEqualTo(SchemaCallReply.Reply(emptyList(), How.Whole))
    }

    @Test
    fun aCallRepeatedUntilTheCapKeepsTheFirst() {
        val got = parse("""{"calls": [{"tool": "set_alarm", "arguments": {"time": "5:30 am"}, "when": {"kind": "at", "day": {"relative": 1}}}, "tool": "set_alarm", "arguments": {"time": "5:30 am"}, "when": {"kind""", "set_alarm")
        assertThat(got.how).isEqualTo(How.FirstCall)
        assertThat(got.calls.single().`when`!!.getJSONObject("day").getInt("relative")).isEqualTo(1)
    }

    @Test
    fun aMissingBraceStillGivesTheToolAndItsArguments() {
        val got = parse("""{"calls": [{"tool": "set_timer", "arguments": {"duration": "10 minutes"}]}""", "set_timer")
        assertThat(got.how).isEqualTo(How.ToolArguments)
        assertThat(got.calls.single().arguments.getString("duration")).isEqualTo("10 minutes")
    }

    @Test
    fun anythingElseIsUnreadableAndToolsNotOfferedAreDropped() {
        assertThat(parse("Sure, I'll set that alarm.", "set_alarm").how).isEqualTo(How.Unreadable)
        // A call nested in "tool" is not a call.
        assertThat(parse("""{"calls": [{"tool": {"name": "open_app", "arguments": {"app_name": "gmail"}}, "when": null}]}""", "open_app").calls).isEmpty()
        assertThat(parse("""{"calls": [{"tool": "make_call", "arguments": {"name": "Priya"}}]}""", "send_text").calls).isEmpty()
        // org.json alone would take unquoted words; this reads JSON only.
        assertThat(parse("""{calls: [{tool: set_alarm, arguments: {}}]}""", "set_alarm").how).isEqualTo(How.Unreadable)
    }
}

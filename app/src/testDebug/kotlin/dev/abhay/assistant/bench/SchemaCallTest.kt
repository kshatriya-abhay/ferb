package dev.abhay.assistant.bench

import com.google.common.truth.Truth.assertThat
import dev.abhay.assistant.tools.SetAlarm
import dev.abhay.assistant.tools.ShowAlarms
import dev.abhay.assistant.tools.Tools
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

/** The phone bench's copy of Computer mode's schema call (timed, t4): its prompt and its schema. */
class SchemaCallTest {
    private val tools = JSONArray(Tools.json(listOf(SetAlarm, ShowAlarms)))

    @Test
    fun thePromptHasTheRulesAndTheRoutedTools() {
        val prompt = SchemaCall.prompt(tools, rewrite = false, timed = true, version = "t4")
        assertThat(prompt).startsWith("You are Ferb, an assistant on an Android phone. Reply with JSON only: {\"calls\": [...]}.")
        assertThat(prompt).contains("You never calculate: no clock time from \"in an hour\"")
        assertThat(prompt).contains("\"name\":\"set_alarm\"")
        assertThat(prompt).contains("\"name\":\"show_alarms\"")
    }

    @Test
    fun theSchemaIsJsonWithATimeOnlyForTimedTools() {
        val schema = JSONObject(SchemaCall.format(tools, rewrite = false, timed = true, version = "t4")).getJSONObject("json_schema").getJSONObject("schema")
        val calls = schema.getJSONObject("properties").getJSONObject("calls").getJSONObject("items").getJSONArray("anyOf")
        val whens = (0 until calls.length()).associate { i ->
            val p = calls.getJSONObject(i).getJSONObject("properties")
            p.getJSONObject("tool").getJSONArray("enum").getString(0) to p.getJSONObject("when").toString()
        }
        assertThat(whens.getValue("show_alarms")).isEqualTo("{\"type\":\"null\"}")
        assertThat(whens.getValue("set_alarm")).contains("\"in\"") // t4: an alarm may be after a length
    }
}

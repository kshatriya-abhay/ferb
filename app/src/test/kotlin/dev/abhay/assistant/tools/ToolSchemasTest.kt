package dev.abhay.assistant.tools

import com.google.common.truth.Truth.assertThat
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.io.File

class ToolSchemasTest {
    @Test
    fun everySchemaIsValidJsonNamedAfterItsTool() {
        Tools.all.forEach { tool ->
            val schema = JSONObject(tool.schema)
            assertThat(schema.getString("name")).isEqualTo(tool.name)
            assertThat(schema.getString("description")).isNotEmpty()
            assertThat(schema.getJSONObject("parameters").getString("type")).isEqualTo("object")
            // Every required parameter is declared.
            val properties = schema.getJSONObject("parameters").getJSONObject("properties")
            val required = schema.getJSONObject("parameters").getJSONArray("required")
            (0 until required.length()).forEach { assertThat(properties.has(required.getString(it))).isTrue() }
        }
        assertThat(Tools.all.map { it.name }).containsNoDuplicates()
    }

    /** Writes the tool JSON the app gives Needle 3, for `mise run tool-check` to test on the desktop. */
    @Test
    fun exportsTheToolJsonForTheDesktopCheck() {
        val dir = File("build/tool-check").apply { mkdirs() }
        listOf("all" to Tools.all, "default" to Tools.all.filter { it.onByDefault }).forEach { (name, tools) ->
            val json = Tools.json(tools)
            assertThat(JSONArray(json).length()).isEqualTo(tools.size)
            File(dir, "$name.json").writeText(json)
        }
        // The cue words, for tool-check's scorer to apply the same "asks first" rule as the app.
        File(dir, "cues.json").writeText(JSONObject(Tools.all.filter { it.cues != null }.associate { it.name to it.cues!!.pattern }).toString(1))
        // Each tool's default bar, so the scorer decides "runs" or "asks" per tool the way the app does.
        File(dir, "bars.json").writeText(JSONObject(Tools.all.associate { it.name to it.defaultRunFrom }).toString(1))
    }

    @Test
    fun aboutFiveToolsAreOnByDefault() {
        // Five or fewer: Needle 3 considers every one for every request.
        assertThat(ToolSettings.enabledNames(ToolSettings.defaults()).size).isAtMost(5)
    }
}

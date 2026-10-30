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
        File(dir, "cues.json").writeText(JSONObject(Tools.all.associate { it.name to ToolConfig.defaults(it).cues!!.pattern }).toString(1))
        // Each tool's default bar, so the scorer decides "runs" or "asks" per tool the way the app does.
        File(dir, "bars.json").writeText(JSONObject(Tools.all.associate { it.name to it.defaultRunFrom }).toString(1))
        // The tools that always ask by default, for laptop.py --decide's "runs" or "asks".
        File(dir, "confirm.json").writeText(JSONArray(Tools.all.filter { it.confirmByDefault }.map { it.name }).toString())
    }

    @Test
    fun everyToolThatNeedsNoSetupIsOnByDefault() {
        // On: no permission, special access, folder or other app to set up first. A request only
        // reaches the tools it mentions (ToolRouter), so more tools on doesn't crowd Needle 3.
        val needsSetup = listOf(MakeCall, SendText, ShowAgenda, AddEvent, AddReminder, AddToList, ShowList, CheckOff)
        assertThat(Tools.all.filter { it.onByDefault }).containsExactlyElementsIn(Tools.all - needsSetup.toSet())
        // Every tool that asks for a permission is among those.
        assertThat(Tools.all.filter { it.permissions.isNotEmpty() }.toSet()).isEqualTo(needsSetup.filter { it.permissions.isNotEmpty() }.toSet())
    }
}

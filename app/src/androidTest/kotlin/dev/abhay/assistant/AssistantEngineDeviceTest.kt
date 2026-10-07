package dev.abhay.assistant

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dev.abhay.assistant.engine.AssistantEngine
import dev.abhay.assistant.engine.NeedleReply
import dev.abhay.assistant.tools.SetVolume
import dev.abhay.assistant.tools.Tool
import dev.abhay.assistant.tools.ToolSettings
import dev.abhay.assistant.tools.Tools
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The bundled model on the phone: it loads from the APK, answers with the default tools, and a
 * request routed to other tools re-initialises the loaded model with just those.
 */
@RunWith(AndroidJUnit4::class)
class AssistantEngineDeviceTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AssistantApp

    private suspend fun calls(request: String, tools: List<Tool>): List<String> {
        val reply = NeedleReply.parse(app.engine.complete(request, tools.map { it.name }, Tools.json(tools)))
        Log.i("AssistantEngineDeviceTest", "$request -> ${reply.calls.map { it.signature() }} (${reply.confidence})")
        return reply.calls.map { it.name }
    }

    @Test
    fun eachRequestGetsItsOwnTools() = runBlocking {
        val defaults = Tools.all.filter { it.name in ToolSettings.enabledNames(ToolSettings.defaults()) }
        app.engine.configure(defaults.map { it.name }, Tools.json(defaults))
        val ready = app.engine.state.value as AssistantEngine.State.Ready
        Log.i("AssistantEngineDeviceTest", "loaded in ${ready.loadMs} ms; ${ready.tools.size} tools, ${ready.prefixTokens} tokens in ${ready.initMs} ms")
        assertThat(calls("Torch off", defaults)).containsExactly("set_flashlight")
        assertThat(calls("Set the media volume to 30 percent", defaults)).isEmpty()

        assertThat(calls("Set the media volume to 30 percent", listOf(SetVolume))).containsExactly("set_volume")
        val again = app.engine.state.value as AssistantEngine.State.Ready
        Log.i("AssistantEngineDeviceTest", "re-initialised in ${again.initMs} ms (${again.prefixTokens} tokens), model not reloaded")
        assertThat(again.tools).containsExactly(SetVolume.name)
        assertThat(again.loadMs).isEqualTo(ready.loadMs)
        assertThat(calls("Torch off", listOf(SetVolume))).isEmpty()
    }
}

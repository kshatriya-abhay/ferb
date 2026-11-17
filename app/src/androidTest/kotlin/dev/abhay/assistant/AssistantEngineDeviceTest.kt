package dev.abhay.assistant

import android.os.SystemClock
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
        // Without its tool, nothing (Set volume is on by default since this was written: left out here).
        assertThat(calls("Set the media volume to 30 percent", defaults - SetVolume)).isEmpty()

        assertThat(calls("Set the media volume to 30 percent", listOf(SetVolume))).containsExactly("set_volume")
        val again = app.engine.state.value as AssistantEngine.State.Ready
        Log.i("AssistantEngineDeviceTest", "re-initialised in ${again.initMs} ms (${again.prefixTokens} tokens), model not reloaded")
        assertThat(again.tools).containsExactly(SetVolume.name)
        assertThat(again.loadMs).isEqualTo(ready.loadMs)
        assertThat(calls("Torch off", listOf(SetVolume))).isEmpty()
    }

    /**
     * Jev mode's phone side, timed (`adb logcat -s AssistantEngineDeviceTest`): Needle 3 re-initialised
     * with the one tool Jev picked, then the call, for a few tools in turn. Jev itself isn't called:
     * it costs money, and device tests make no live Jev calls.
     */
    @Test
    fun jevModesPhoneSideCallsTheOneTool() = runBlocking<Unit> {
        val picks = listOf("set_alarm" to "wake me up at 6:30", "set_timer" to "timer for 10 minutes",
            "set_volume" to "volume to 40 percent", "control_media" to "pause the music", "open_app" to "open spotify")
        // The model loaded first, as it is in the chat; only the re-init and the call are timed.
        app.engine.configure(listOf(SetVolume.name), Tools.json(listOf(SetVolume)))
        val times = picks.map { (name, request) ->
            val tool = Tools.byName(name)!!
            val started = SystemClock.elapsedRealtime()
            val got = calls(request, listOf(tool))
            val ms = SystemClock.elapsedRealtime() - started
            val ready = app.engine.state.value as AssistantEngine.State.Ready
            Log.i("AssistantEngineDeviceTest", "jev mode, $name: $ms ms in all (re-init ${ready.initMs} ms) -> $got")
            assertThat(got).containsExactly(name)
            ms
        }
        Log.i("AssistantEngineDeviceTest", "jev mode, Needle 3 with one tool: median ${times.sorted()[times.size / 2]} ms, max ${times.max()} ms")
    }

    /**
     * Review P2-95: requests reach the engine through JNI's GetStringUTFChars, modified UTF-8, in
     * which an emoji is two 3-byte surrogates, not UTF-8. A note with Devanagari and an emoji: what
     * the engine heard comes back in the call's arguments, logged, and checked for both.
     */
    @Test
    fun devanagariAndAnEmojiReachTheEngineIntact() = runBlocking<Unit> {
        val note = listOf(dev.abhay.assistant.tools.CreateNote)
        val reply = NeedleReply.parse(app.engine.complete("Take a note: दूध और ब्रेड लाना 🛒", note.map { it.name }, Tools.json(note)))
        val args = reply.calls.joinToString { it.arguments.toString() }
        Log.i("AssistantEngineDeviceTest", "note -> ${reply.calls.map { it.signature() }}")
        assertThat(args).contains("दूध")
        assertThat(args).contains("🛒")
    }
}

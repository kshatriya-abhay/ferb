package dev.abhay.assistant

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.abhay.assistant.bench.PhoneBench
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The phone bench's Needle 3 pass, with no screen (tool-check/phone.py needle): Needle 3 needs no
 * foreground, unlike Gemini Nano. Skipped unless the bench's requests were pushed; run on its own
 * (-e class dev.abhay.assistant.PhoneBenchTest), never beside another model.
 */
@RunWith(AndroidJUnit4::class)
class PhoneBenchTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AssistantApp

    @Test
    fun needle3() {
        // -e input requests-X.jsonl -e output needle3-X: Needle 3 on Gemini Nano's rewrites (phone.py).
        val args = InstrumentationRegistry.getArguments()
        val input = args.getString("input") ?: PhoneBench.REQUESTS
        val output = args.getString("output")
        val bench = PhoneBench(app)
        assumeTrue("no bench requests pushed", bench.items(input).isNotEmpty())
        val result = runBlocking { bench.run(PhoneBench.Model.Needle3, input = input, output = output) { done, total, _ -> if (done % 25 == 0) Log.i(PhoneBench.TAG, "needle3 $done/$total") } }
        Log.i(PhoneBench.TAG, result)
    }

    /**
     * A Gemini Nano pass (-e model Blind2R, a [PhoneBench.Model] name), driven with no taps: the
     * chat is launched and kept in front with the screen on, as Gemini Nano needs. Skipped unless
     * the model is named and its lines were pushed.
     */
    @Test
    fun gemini() {
        val name = InstrumentationRegistry.getArguments().getString("model")
        assumeTrue("-e model <name> for a Gemini Nano pass", name != null)
        val model = PhoneBench.Model.valueOf(name!!)
        val bench = PhoneBench(app)
        assumeTrue("no lines pushed for ${model.file}", bench.items(model.input).isNotEmpty())
        androidx.test.core.app.ActivityScenario.launch(ChatActivity::class.java).use { scenario ->
            scenario.onActivity { it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            // Gemini Nano refuses an app not yet in front (ErrorCode 30): wait for it, and try once more if refused.
            repeat(100) { if (app.foreground.inFront.value) return@repeat; Thread.sleep(100) }
            Thread.sleep(1_000)
            fun pass() = runBlocking { bench.run(model) { done, total, error -> if (done % 20 == 0 || error != null) Log.i(PhoneBench.TAG, "${model.file} $done/$total ${error ?: ""}") } }
            val result = runCatching { pass() }.getOrElse { e ->
                Log.w(PhoneBench.TAG, "first try: $e"); Thread.sleep(3_000); pass()
            }
            Log.i(PhoneBench.TAG, result)
            org.junit.Assert.assertFalse(result, result.contains("stopped") || result.contains("failed"))
        }
    }
}

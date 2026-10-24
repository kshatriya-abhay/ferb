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
}

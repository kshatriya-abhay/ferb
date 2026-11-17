package dev.abhay.assistant

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.abhay.assistant.voice.EnergyVad
import dev.abhay.assistant.voice.PcmRecorder
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Review P2-97: how a real recording starts on this phone. Records about 1.5 s of the room with
 * Ferb in front (Android silences a background mic) and logs each 20 ms frame's energy for the
 * first 600 ms (`adb logcat -s MicStart`), to see whether it opens on digital silence (below
 * EnergyVad.DIGITAL_SILENCE_DB), which once pinned the noise floor. Asked for by name: `-e mic 1`.
 */
@InFront
@RunWith(AndroidJUnit4::class)
class MicStartDeviceTest {
    @Test
    fun aRecordingsFirstFrames() = runBlocking<Unit> {
        assumeTrue("-e mic 1", InstrumentationRegistry.getArguments().getString("mic") == "1")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(android.content.Intent(context, ChatActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            val recorder = PcmRecorder()
            val clip = async { recorder.record() }
            delay(1_500)
            recorder.stop()
            val pcm = clip.await()
            val frame = PcmRecorder.SAMPLE_RATE * EnergyVad.FRAME_MS / 1000
            val energies = (0 until minOf(30, pcm.size / frame)).map { i -> EnergyVad.energyDb(pcm.copyOfRange(i * frame, (i + 1) * frame)) }
            Log.i("MicStart", "first ${energies.size} frames (dBFS): " + energies.joinToString(" ") { "%.0f".format(it) })
            Log.i("MicStart", "below ${EnergyVad.DIGITAL_SILENCE_DB}: ${energies.count { it < EnergyVad.DIGITAL_SILENCE_DB }} frames; samples ${pcm.size}")
        } finally {
            activity.finish()
        }
    }
}

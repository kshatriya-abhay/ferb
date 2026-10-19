package dev.abhay.assistant

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dev.abhay.assistant.link.LinkStatus
import dev.abhay.assistant.tools.AddReminder
import dev.abhay.assistant.tools.SetAlarm
import dev.abhay.assistant.tools.TimeArgs
import dev.abhay.assistant.tools.Tools
import dev.abhay.assistant.ui.VoiceOutcome
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.ZonedDateTime
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Ferb against the installed Ferb Link, through its provider (no UI): status, check, complete, and
 * transcribe with a silent clip. Needs Ferb Link installed and set up; complete and transcribe also
 * need the computer reachable, and are skipped (logged) when it isn't.
 */
@RunWith(AndroidJUnit4::class)
class FerbLinkDeviceTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AssistantApp

    @Test
    fun contractWithTheInstalledFerbLink() = runBlocking {
        val status = app.link.status()
        Log.i(TAG, "status: $status")
        assumeTrue("Ferb Link installed", status != LinkStatus.NotInstalled)
        assertThat(status).isNotInstanceOf(LinkStatus.Mismatch::class.java)
        assertThat(status).isNotEqualTo(LinkStatus.NotAllowed)
        assumeTrue("Ferb Link set up and on", status is LinkStatus.Ready)

        val check = app.link.check()
        Log.i(TAG, "check: $check")
        assertThat(check).isNotNull()
        assumeTrue("the computer answers: ${check?.error} / ${check?.errorDetail}", check!!.reachable)

        val reply = app.link.complete("set an alarm for 7 am", Tools.json(listOf(SetAlarm)))
        Log.i(TAG, "complete: ${reply.calls.map { it.signature() }} ${reply.error} ${reply.ms} ms")
        assertThat(reply.error).isNull()
        assertThat(reply.fromLaptop).isTrue()
        assertThat(reply.calls.map { it.name }).containsExactly("set_alarm")

        val silence = app.link.transcribe(silentWav(2), "audio/wav")
        Log.i(TAG, "transcribe (silence): heard='${silence.heard}' text='${silence.text}' error=${silence.error}")
        assertThat(silence.error).isNull()
        assertThat((VoiceOutcome.of(silence) as VoiceOutcome.Failed).keepClip).isFalse() // "No words heard"
    }

    /**
     * The timed call (`time_format` 1, the path Computer mode takes when Jev picks a timed tool),
     * without Jev: its time structure reaches Ferb and Ferb's resolver takes it.
     */
    @Test
    fun theTimedCallCarriesATimeStructure() = runBlocking {
        assumeTrue("Ferb Link ready", app.link.status() is LinkStatus.Ready)
        assumeTrue("the computer answers", app.link.check()?.reachable == true)
        val request = "remind me tomorrow at 9 am to water the plants"
        val reply = app.link.complete(request, Tools.json(listOf(AddReminder)), timeFormat = true)
        Log.i(TAG, "timed complete: ${reply.calls.map { it.signature() }} ${reply.error} ${reply.ms} ms")
        assertThat(reply.error).isNull()
        val call = reply.calls.single()
        assertThat(call.name).isEqualTo(AddReminder.name)
        assertThat(call.arguments).containsKey("when")
        val applied = TimeArgs.apply(call.name, call.arguments, listOf(request), ZonedDateTime.now())
        Log.i(TAG, "timed applied: $applied")
        assertThat(applied[TimeArgs.RESOLVED]).isEqualTo(true)
    }

    /**
     * A spoken request, made by the phone's own text-to-speech into a file (nothing is played),
     * transcribed by the computer and answered as Ferb would.
     */
    @Test
    fun aSpokenClipIsTranscribed() = runBlocking {
        assumeTrue("Ferb Link ready", app.link.status() is LinkStatus.Ready)
        assumeTrue("the computer answers", app.link.check()?.reachable == true)
        val clip = speak("Set a timer for ten minutes")
        assumeTrue("text-to-speech on this phone", clip != null)
        val heard = app.link.transcribe(clip!!, "audio/wav")
        Log.i(TAG, "transcribe (speech): heard='${heard.heard}' text='${heard.text}' error=${heard.error} ${heard.ms} ms")
        assertThat(heard.error).isNull()
        assertThat(heard.text.lowercase()).contains("timer")
        assertThat(VoiceOutcome.of(heard)).isInstanceOf(VoiceOutcome.Answer::class.java)
    }

    /** [text] as a WAV clip from the phone's text-to-speech, written to a file and never played; null without an engine. */
    private fun speak(text: String): ByteArray? {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = java.io.File(context.cacheDir, "tts-clip.wav")
        val ready = java.util.concurrent.CountDownLatch(1)
        val done = java.util.concurrent.CountDownLatch(1)
        var status = android.speech.tts.TextToSpeech.ERROR
        lateinit var tts: android.speech.tts.TextToSpeech
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            tts = android.speech.tts.TextToSpeech(context) { status = it; ready.countDown() }
        }
        try {
            if (!ready.await(10, java.util.concurrent.TimeUnit.SECONDS) || status != android.speech.tts.TextToSpeech.SUCCESS) return null
            tts.language = java.util.Locale.UK
            tts.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) = done.countDown()
                @Deprecated("old API") override fun onError(id: String?) = done.countDown()
            })
            if (tts.synthesizeToFile(text, null, file, "clip") != android.speech.tts.TextToSpeech.SUCCESS) return null
            if (!done.await(20, java.util.concurrent.TimeUnit.SECONDS) || file.length() < 1000) return null
            return file.readBytes()
        } finally {
            tts.shutdown()
            file.delete()
        }
    }

    /** [seconds] of 16 kHz mono 16-bit silence as a WAV file. */
    private fun silentWav(seconds: Int): ByteArray {
        val data = ByteArray(16_000 * 2 * seconds)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + data.size); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(16_000); putInt(32_000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(data.size)
        }.array()
        return ByteArrayOutputStream().apply { write(header); write(data) }.toByteArray()
    }

    private companion object {
        const val TAG = "FerbLinkDeviceTest"
    }
}

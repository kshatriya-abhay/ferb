package dev.abhay.assistant

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dev.abhay.assistant.link.LinkStatus
import dev.abhay.assistant.voice.GeminiRewrite
import dev.abhay.assistant.ui.VoiceOutcome
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Ferb against the installed Ferb Link, through its provider (no UI): status, check, rewrite, and
 * transcribe with a silent clip. Needs Ferb Link installed and set up; rewrite and transcribe also
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

        // The rewrite step on the computer: Ferb's own prompt, read by Ferb's own rules.
        val reply = app.link.rewrite("kal subah 7 baje utha dena", GeminiRewrite.PROMPT, GeminiRewrite.MAX_TOKENS)
        Log.i(TAG, "rewrite: raw='${reply.raw}' instructed=${reply.instructed} ${reply.error} ${reply.ms} ms")
        assertThat(reply.error).isNull()
        assertThat(reply.instructed).isTrue()
        assertThat(GeminiRewrite.parse("kal subah 7 baje utha dena", reply.raw!!).command?.lowercase()).contains("7")

        val silence = app.link.transcribe(silentWav(2), "audio/wav")
        Log.i(TAG, "transcribe (silence): heard='${silence.heard}' text='${silence.text}' error=${silence.error}")
        assertThat(silence.error).isNull()
        assertThat((VoiceOutcome.of(silence) as VoiceOutcome.Failed).keepClip).isFalse() // "No words heard"
    }

    /** Ferb's AAC recording (the audio step on the computer) is one Ferb Link takes: silence comes back as "no words". */
    @Test
    fun anAacRecordingIsHeard() = runBlocking {
        assumeTrue("Ferb Link ready", app.link.status() is LinkStatus.Ready)
        assumeTrue("the computer answers", app.link.check()?.reachable == true)
        val aac = dev.abhay.assistant.voice.AacEncoder.encode(FloatArray(16_000 * 2), 16_000, app.cacheDir)
        assertThat(aac.size).isLessThan(64 * 1024)
        val heard = app.link.transcribe(aac, dev.abhay.assistant.voice.AacEncoder.MIME)
        Log.i(TAG, "transcribe (AAC silence): ${aac.size} bytes, heard='${heard.heard}' error=${heard.error}")
        assertThat(heard.error).isNull()
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

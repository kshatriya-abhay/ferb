package dev.abhay.assistant

import android.media.AudioManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dev.abhay.assistant.tools.SetVolume
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The volume slider's watch, in the background (no screen): the media volume moved one step from
 * outside the watch (silently, no UI flag) reaches it, then the volume is put back as it was.
 */
@RunWith(AndroidJUnit4::class)
class VolumeWatchDeviceTest {
    @Test
    fun aVolumeChangeOutsideFerbReachesTheWatch() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val audio = context.getSystemService(AudioManager::class.java)
        val stream = AudioManager.STREAM_MUSIC
        val before = audio.getStreamVolume(stream)
        val max = audio.getStreamMaxVolume(stream)
        val target = if (before > 0) before - 1 else 1
        var seen = -1f
        val heard = CountDownLatch(1)
        val stop = SetVolume.watch.start(context) { now -> seen = now; heard.countDown() }
        try {
            audio.setStreamVolume(stream, target, 0)
            assertThat(heard.await(3, TimeUnit.SECONDS)).isTrue()
            Log.i("VolumeWatchDeviceTest", "volume $before → $target of $max; the watch saw $seen")
            assertThat(seen).isWithin(0.001f).of(target.toFloat() / max)
        } finally {
            stop()
            audio.setStreamVolume(stream, before, 0)
        }
    }
}

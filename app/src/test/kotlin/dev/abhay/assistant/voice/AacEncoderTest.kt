package dev.abhay.assistant.voice

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import org.junit.Test

class AacEncoderTest {
    @Test
    fun aFailedEncodeLeavesNoFileInTheCache() {
        // Review P2-96: the temp file was made before the try, so a codec that couldn't be made left it.
        val cache = Files.createTempDirectory("aac").toFile()
        // On the JVM the codec can't be made, as on a phone with no encoder free.
        runCatching { AacEncoder.encode(FloatArray(16_000), 16_000, cache) }
        assertThat(cache.list()!!.toList()).isEmpty()
        cache.deleteRecursively()
    }
}

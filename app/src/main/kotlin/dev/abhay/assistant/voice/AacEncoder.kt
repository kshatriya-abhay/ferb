package dev.abhay.assistant.voice

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteOrder

/**
 * A phone recording (mono float PCM at 16 kHz) as AAC in an MP4 file, for Ferb Link (audio
 * processing on the computer): small enough for its 512 KB limit (30 s is about 120 KB, where WAV
 * would be 960 KB), and Ferb Link decodes it with Android's own MediaExtractor. Android's encoder,
 * on the phone; nothing leaves it until Ferb Link sends the file. The same PCM goes to Whistle if
 * the computer can't hear it.
 */
object AacEncoder {
    const val MIME = "audio/mp4"
    private const val BIT_RATE = 32_000
    private const val TIMEOUT_US = 10_000L

    fun encode(pcm: FloatArray, rate: Int, cacheDir: File): ByteArray {
        val file = File.createTempFile("voice", ".m4a", cacheDir)
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            codec.configure(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val samples = Pcm16.of(pcm)
            var read = 0
            var inputDone = false
            var track = -1
            val info = MediaCodec.BufferInfo()
            while (true) {
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index)!!
                        val count = minOf(buffer.remaining() / 2, samples.size - read)
                        buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples, read, count)
                        val timeUs = read * 1_000_000L / rate
                        read += count
                        inputDone = read >= samples.size
                        codec.queueInputBuffer(index, 0, count * 2, timeUs, if (inputDone) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                    }
                }
                val out = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                    }
                    out >= 0 -> {
                        val buffer = codec.getOutputBuffer(out)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0 && track >= 0) muxer.writeSampleData(track, buffer, info)
                        codec.releaseOutputBuffer(out, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
            muxer.stop()
            return file.readBytes()
        } finally {
            runCatching { codec.release() }
            runCatching { muxer.release() }
            file.delete()
        }
    }
}

/** Float PCM in [-1, 1] as 16-bit samples, clipped. */
object Pcm16 {
    fun of(pcm: FloatArray): ShortArray = ShortArray(pcm.size) { i -> (pcm[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort() }
}

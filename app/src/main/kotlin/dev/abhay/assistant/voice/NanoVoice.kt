package dev.abhay.assistant.voice

import com.google.mlkit.genai.common.FeatureStatus
import java.util.Locale

/**
 * Who hears a spoken request in Phone mode (Settings, under "Voice on this phone"). Whistle is the
 * default; Gemini Nano is a proof of concept (branch poc/gemini-nano): ML Kit GenAI Speech
 * Recognition in Advanced mode, on the Gemini model AICore keeps on Pixel 10 and 11.
 */
enum class PhoneEar(val id: String, val title: String, val locale: Locale?) {
    Whistle("whistle", "Whistle (English)", null),
    NanoEnglish("nano-en", "Gemini Nano (English)", Locale.forLanguageTag("en-US")),
    NanoHindi("nano-hi", "Gemini Nano (Hindi)", Locale.forLanguageTag("hi-IN"));

    val nano get() = locale != null

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: Whistle
        val NANO = entries.filter { it.nano }
    }
}

/** What Gemini Nano's checkStatus() means for a spoken request. */
sealed interface NanoChoice {
    /** Gemini Nano hears it. */
    data object Nano : NanoChoice
    /** Whistle hears it instead, with [notice] saying why; [download]: start the model's download. */
    data class Whistle(val notice: String, val download: Boolean = false) : NanoChoice

    companion object {
        fun of(status: Int): NanoChoice = when (status) {
            FeatureStatus.AVAILABLE -> Nano
            FeatureStatus.DOWNLOADABLE -> Whistle(DOWNLOADING, download = true)
            FeatureStatus.DOWNLOADING -> Whistle(DOWNLOADING)
            else -> Whistle(UNAVAILABLE)
        }

        const val DOWNLOADING = "Gemini Nano's speech model is downloading: Whistle hears this one"
        const val UNAVAILABLE = "Gemini Nano can't hear on this phone: Whistle hears it"

        /** For the log and Settings. */
        fun name(status: Int): String = when (status) {
            FeatureStatus.AVAILABLE -> "available"
            FeatureStatus.DOWNLOADABLE -> "downloadable"
            FeatureStatus.DOWNLOADING -> "downloading"
            FeatureStatus.UNAVAILABLE -> "unavailable"
            else -> "unknown ($status)"
        }
    }
}

/**
 * The chat box's text while Gemini Nano listens: what was typed [before], each final piece as it
 * comes, then the piece still being heard (a partial result replaces the one before it).
 */
class LiveTranscript(private val before: String = "") {
    private val finals = mutableListOf<String>()
    private var partial = ""

    fun partial(text: String): String {
        partial = text.trim()
        return text()
    }

    fun final(text: String): String {
        text.trim().takeIf { it.isNotEmpty() }?.let(finals::add)
        partial = ""
        return text()
    }

    /** Only what was heard, without [before]. */
    val heard get() = (finals + partial).filter { it.isNotEmpty() }.joinToString(" ")

    fun text(): String = listOf(before.trim(), heard).filter { it.isNotEmpty() }.joinToString(" ")
}

package dev.abhay.assistant.voice

import com.google.mlkit.genai.common.FeatureStatus
import org.json.JSONObject
import java.util.Locale

/**
 * Who writes down a spoken request on the phone (Settings → Voice), with "Use voice to text
 * on-device" on. Whistle is the default; Gemini Nano is a proof of concept (branch
 * poc/gemini-nano): ML Kit GenAI Speech Recognition in Advanced mode, then the Prompt API writes
 * what was heard as a plain English command for Needle 3, as Computer mode does.
 */
enum class PhoneEar(val id: String, val title: String, val hint: String) {
    Whistle("whistle", "Whistle", "On-device, English only"),
    Gemini("gemini", "Gemini Nano", "On-device");

    companion object {
        /** "nano-en" and "nano-hi" were the first POC build's two Gemini choices. */
        fun of(id: String?) = when (id) {
            "gemini", "nano-en", "nano-hi" -> Gemini
            else -> Whistle
        }
    }
}

/**
 * The language Gemini Nano expects. One model hears both: set to English it still wrote Hindi
 * down, in Devanagari (2026-10-24), and both locales report the same AICore feature
 * (nano-v4-full, "Speech Recognition Continuous Streaming"). Japanese wasn't heard.
 */
enum class GeminiLanguage(val id: String, val title: String, val locale: Locale) {
    English("en-US", "English", Locale.forLanguageTag("en-US")),
    Hindi("hi-IN", "Hindi or Hinglish", Locale.forLanguageTag("hi-IN"));

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: English
    }
}

/** Where one part of Gemini Nano's setup stands on this phone: speech recognition, or the rewriting model. */
sealed interface Part {
    data object Checking : Part
    data object Ready : Part
    data object NeedsDownload : Part
    /** [done] bytes of [total] (null until AICore says how much). */
    data class Downloading(val done: Long = 0, val total: Long? = null) : Part {
        val fraction: Float? get() = total?.takeIf { it > 0 }?.let { (done.toFloat() / it).coerceIn(0f, 1f) }
    }
    data object Unavailable : Part
    data class Failed(val why: String) : Part

    /** For Settings. */
    fun label(): String = when (this) {
        Checking -> "Checking…"
        Ready -> "Ready"
        NeedsDownload -> "Needs a download"
        is Downloading -> when {
            total != null && total > 0 -> "Downloading: ${mb(done)} of ${mb(total)} MB"
            done > 0 -> "Downloading: ${mb(done)} MB so far"
            else -> "Downloading…"
        }
        Unavailable -> "Not available on this phone"
        is Failed -> "Couldn't download: $why"
    }

    companion object {
        /** What checkStatus() says. DOWNLOADING without numbers: Android is fetching it; Set up follows it. */
        fun of(status: Int): Part = when (status) {
            FeatureStatus.AVAILABLE -> Ready
            FeatureStatus.DOWNLOADABLE -> NeedsDownload
            FeatureStatus.DOWNLOADING -> Downloading()
            else -> Unavailable
        }

        private fun mb(bytes: Long) = String.format(Locale.US, if (bytes < 10_000_000) "%.1f" else "%.0f", bytes / 1_000_000.0)
    }
}

/** Gemini Nano's whole setup: what it needs to hear [language] and to write it in English. */
data class GeminiSetup(val speech: Part = Part.Checking, val rewrite: Part = Part.Checking) {
    val ready get() = speech == Part.Ready && rewrite == Part.Ready
    val parts get() = listOf(speech, rewrite)
    /** Something to fetch: Set up downloads it. */
    val needsSetUp get() = parts.any { it == Part.NeedsDownload || it is Part.Failed || (it is Part.Downloading && it.total == null && it.done == 0L) }
    val downloading get() = parts.any { it is Part.Downloading }

    /** The Settings row's line under "Gemini Nano". */
    fun summary(): String = when {
        ready -> "Ready"
        parts.any { it == Part.Unavailable } -> "Not available on this phone"
        downloading -> parts.filterIsInstance<Part.Downloading>().let { d ->
            val total = d.mapNotNull { it.total }.sum()
            if (total > 0 && d.all { it.total != null }) "Downloading: ${(d.sumOf { it.done } * 100 / total)}%" else "Downloading…"
        }
        parts.any { it == Part.Checking } -> "Checking…"
        else -> "Set up needed: tap to download"
    }
}

/** What Gemini Nano's checkStatus() means for a spoken request. */
sealed interface NanoChoice {
    /** Gemini Nano hears it. */
    data object Nano : NanoChoice
    /** Whistle hears it instead, with [notice] saying why. */
    data class Whistle(val notice: String) : NanoChoice

    companion object {
        fun of(status: Int): NanoChoice = when (status) {
            FeatureStatus.AVAILABLE -> Nano
            FeatureStatus.DOWNLOADABLE -> Whistle(NOT_SET_UP)
            FeatureStatus.DOWNLOADING -> Whistle(DOWNLOADING)
            else -> Whistle(UNAVAILABLE)
        }

        const val NOT_SET_UP = "Gemini Nano isn't set up yet (Settings → Gemini Nano): Whistle hears this one"
        const val DOWNLOADING = "Gemini Nano is still downloading: Whistle hears this one"
        const val UNAVAILABLE = "Gemini Nano can't hear on this phone: Whistle hears it"

        /** For the log. */
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
 * What Gemini Nano hears while listening: each final piece as it comes, then the piece still being
 * heard (a partial result replaces the one before it).
 */
class LiveTranscript {
    private val finals = mutableListOf<String>()
    private var partial = ""

    fun partial(text: String): String {
        partial = text.trim()
        return heard
    }

    fun final(text: String): String {
        text.trim().takeIf { it.isNotEmpty() }?.let(finals::add)
        partial = ""
        return heard
    }

    val heard get() = (finals + partial).filter { it.isNotEmpty() }.joinToString(" ")
}

/**
 * What Gemini Nano heard, written for Needle 3 (Computer mode's step, on the phone): [heard] as
 * said, in Latin letters, and [text] as a plain English command. English goes on as heard.
 */
data class Rewritten(val heard: String, val text: String, val hindi: Boolean)

/**
 * The rewrite before Needle 3: Ferb Link's transcribe prompt (ServerClient.TRANSCRIBE, its lessons
 * from the audio bench) for text Gemini Nano wrote down, which may be in Devanagari.
 */
object GeminiRewrite {
    const val PROMPT = """You get a voice request to a phone assistant as speech recognition wrote it down. It may be English, Hindi or Hinglish, in Latin letters or in Devanagari. Reply with JSON only, with three fields, in this order: {"hindi": ..., "heard": "...", "text": "..."}
- "hindi": true if any word was Hindi (Hinglish counts), false if every word was English.
- "heard": the same words, in Latin letters. Write Hindi words the way people type Hinglish ("subah 5 baje ka alarm laga do"), never in Devanagari. Keep English words in English. Write numbers as digits.
- "text": the same request as a short, plain English command, for example "Set an alarm for 5 am". Translate only: keep every name, app, place, number, time and date exactly as in "heard", don't add, drop or guess anything, and don't answer the request.
Hindi amounts: sava (a quarter more: sava saat = 7:15, sava ghanta = 1 hour 15 minutes), saade (a half more: saade paanch = 5:30), paune (a quarter less: paune aath = 7:45), dedh (one and a half: dedh ghanta = 1 hour 30 minutes), dhai (two and a half: dhai minute = 2 minutes 30 seconds, dhai baje = 2:30). In "text" write the exact time or length ("Wake me up at 7:15").
In "text", write other amounts as numbers too: half is 50 percent, a quarter 25 percent, full or max 100 percent ("aadhi aawaz" becomes "Set the volume to 50 percent"). A request of one or two action words is still a command: "next", "stop", "pause", "louder" stay as said."""

    /**
     * Common Hindi words in Latin letters (Ferb Link's HINDI_WORDS): only words that aren't also
     * everyday English, so an English request goes straight to Needle 3.
     */
    private val HINDI_WORDS = setOf(
        "kholo", "karo", "kar", "kardo", "laga", "lagao", "lagado", "chalao", "chalu", "bajao", "bhejo", "batao", "dikhao",
        "ka", "ki", "ke", "ko", "hai", "hain", "mein", "se", "mujhe", "mera", "meri", "mere", "kya", "kaise", "kaisa",
        "baje", "kal", "aaj", "subah", "shaam", "raat", "ghante", "ghanta", "dedh", "dhai", "abhi", "jaldi", "nahi",
        "aur", "wala", "wali", "rasta", "liye", "saath", "daal", "dalo", "bolo", "sunao", "yaad", "dilana",
    )

    /** Whether [heard] needs the rewrite: any Devanagari, or a Hindi word in Latin letters. */
    fun needed(heard: String): Boolean =
        heard.any { it in 'ऀ'..'ॿ' } || heard.lowercase().split(Regex("[^a-z]+")).any { it in HINDI_WORDS }

    /**
     * The model's answer, read: the first JSON object in it. English ("hindi" false) keeps what was
     * heard, as Computer mode does: the model once "translated" English and dropped a "not".
     */
    fun parse(heard: String, answer: String): Rewritten {
        val start = answer.indexOf('{')
        val end = answer.lastIndexOf('}')
        require(start >= 0 && end > start) { "no JSON in Gemini Nano's answer" }
        val o = JSONObject(answer.substring(start, end + 1))
        val hindi = o.optBoolean("hindi", needed(heard))
        val latin = o.optString("heard").trim().ifEmpty { heard }
        val text = o.optString("text").trim()
        require(text.isNotEmpty() || !hindi) { "Gemini Nano wrote no command" }
        return if (hindi) Rewritten(latin, text, true) else Rewritten(heard, heard, false)
    }
}

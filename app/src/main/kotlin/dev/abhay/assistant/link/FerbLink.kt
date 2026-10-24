package dev.abhay.assistant.link

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.ContentProviderClient
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.DeadObjectException
import android.os.SystemClock
import android.util.Log
import androidx.core.os.bundleOf
import dev.abhay.assistant.engine.NeedleReply
import dev.abhay.assistant.engine.ToolCall
import dev.abhay.assistant.tools.TimeArgs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Ferb's copy of Ferb Link's contract (`LinkContract` in /data/Projects/ferb-link): Ferb can't
 * depend on that app's code, so the names are repeated here; change both together.
 */
object LinkContract {
    const val PACKAGE = "dev.abhay.ferblink"
    const val AUTHORITY = "dev.abhay.ferblink.link"

    /** The only version Ferb understands; any other means one of the two apps needs updating. */
    const val VERSION = 1

    const val METHOD_STATUS = "status"
    const val METHOD_COMPLETE = "complete"
    /** Added after version 1 without a bump: an older Ferb Link throws IllegalArgumentException. */
    const val METHOD_CHECK = "check"
    /**
     * Voice (added after version 1, like check): `wake` loads the computer's model if it's asleep,
     * answering at once, and `transcribe` turns a recording into text. An older Ferb Link throws
     * IllegalArgumentException.
     */
    const val METHOD_WAKE = "wake"
    const val METHOD_TRANSCRIBE = "transcribe"
    /**
     * Jev (TypeSafe's cloud model, opt-in in Ferb Link): which of [EXTRA_TOOLS] fits [EXTRA_REQUEST],
     * or "none", with each option's probability. Ferb Link sends Jev only the request and the
     * tools' names and descriptions. Added after version 1: an older Ferb Link throws
     * IllegalArgumentException.
     */
    const val METHOD_DECIDE = "decide"
    /**
     * The computer's model turning a typed request into plain English commands, for Jev and
     * Needle 3: for Hinglish, which neither understands. Ferb doesn't use it yet: a "comprehend"
     * step (the computer deciding whether a request needs it) is being benched first. Ferb Link resolves times
     * and dates against the phone's clock. Added after version 1: an older Ferb Link throws
     * IllegalArgumentException.
     */
    const val METHOD_REWRITE = "rewrite"

    const val EXTRA_REQUEST = "request"
    const val EXTRA_TOOLS = "tools"
    /** How long Ferb Link may wait for the computer (1–60 s); an older Ferb Link ignores it and waits 30 s. */
    const val EXTRA_TIMEOUT_MS = "timeout_ms"
    /** `rewrite`: the caller's own system prompt (at most 16000 characters), and the answer's most tokens (1–256, 40 by default). */
    const val EXTRA_INSTRUCTIONS = "instructions"
    const val EXTRA_MAX_TOKENS = "max_tokens"
    /**
     * For `complete`: 1 asks for each call's time as structure ("time" v1) in its `when`, through a
     * schema-constrained call instead of native tool calls, with `trimmed` and `asks` per call
     * (Plan - Dates and times through Gemma). An older Ferb Link ignores it and answers natively.
     */
    const val EXTRA_TIME_FORMAT = "time_format"
    /** For `transcribe`: the recording (byte[], at most 30 s and about 512 KB) and its type. */
    const val EXTRA_AUDIO = "audio"
    const val EXTRA_AUDIO_MIME = "audio_mime"
    /** Reserved for names that help transcription (a string array); unused: they'd leave the phone. */
    const val EXTRA_HINTS = "hints"

    const val RESULT_VERSION = "version"
    const val RESULT_ENABLED = "enabled"
    const val RESULT_CONFIGURED = "configured"
    const val RESULT_REPLY = "reply"
    /** In `status` and `check`: the model the computer runs, last seen; empty or missing when unknown. */
    const val RESULT_MODEL = "model"
    /** In `check`. */
    const val RESULT_REACHABLE = "reachable"
    const val RESULT_ERROR = "error"
    /** In `check` (and in replies' JSON): the failure at length; [RESULT_ERROR] is a few words. Missing in an older Ferb Link. */
    const val RESULT_ERROR_DETAIL = "error_detail"
    /**
     * In `status` and `check`: whether the computer's model takes audio, so voice works; missing in
     * an older Ferb Link. Status keeps the last value seen, false until a check, wake or complete.
     */
    const val RESULT_AUDIO = "audio"
    /**
     * In `status` and `check`: the computer's model as last seen, "loaded", "asleep" or "unloaded";
     * missing in an older Ferb Link, which counts as loaded.
     */
    const val RESULT_MODEL_STATE = "model_state"
    /** In `status`: whether Jev is on in Ferb Link, with a key; missing (false) in an older one. */
    const val RESULT_JEV = "jev"
    /** In `status`, when Jev is off: "cap" when it turned itself off at its spending limit; missing when the user turned it off. */
    const val RESULT_JEV_OFF_REASON = "jev_off_reason"
}

/** Whether Ferb can use Ferb Link now, as its settings page shows it. */
sealed interface LinkStatus {
    data object NotInstalled : LinkStatus
    /** Ferb Link answers with another contract version: one of the two apps is older. */
    data class Mismatch(val version: Int) : LinkStatus
    /** Ferb Link refuses Ferb: the two apps aren't signed with the same key. */
    data object NotAllowed : LinkStatus
    /** Ferb Link is installed but its "Answer Ferb's requests" switch is off. */
    data object Off : LinkStatus
    /** No server address and key entered in Ferb Link yet. */
    data object NotSetUp : LinkStatus
    /** [model]: the computer's model as Ferb Link last saw it, when it says; [audio]: whether it takes voice. */
    /** [asleep]: the computer's model isn't loaded (asleep or unloaded, as last seen), so the next request waits for it to load. */
    /** [jev]: Jev is on in Ferb Link ([LinkContract.METHOD_DECIDE]); [jevCapped]: it turned itself off at its spending limit. */
    data class Ready(
        val model: String? = null,
        val audio: Boolean = false,
        val asleep: Boolean = false,
        val jev: Boolean = false,
        val jevCapped: Boolean = false,
    ) : LinkStatus
    data class Failed(val reason: String) : LinkStatus
}

/**
 * Ferb Link, the companion app that holds the INTERNET permission Ferb must never have and passes
 * requests to a bigger model on the user's own computer (over Tailscale). Ferb talks to it through
 * one `ContentResolver.call`, so Ferb itself stays offline; Needle 3 answers whenever Ferb Link
 * can't.
 */
class FerbLink(private val context: Context) : LinkCalls {
    /**
     * Held while a step uses the computer ([hold]): Android then keeps Ferb Link's process
     * at Ferb's importance instead of killing it, which cost about 250 ms on the next call (twice
     * in the audio bench, 2026-10-12). Unstable, so Ferb isn't killed if Ferb Link crashes.
     */
    @Volatile private var client: ContentProviderClient? = null
    private var holders = 0

    /** Keeps Ferb Link's process up until a matching [release]. Main thread. */
    fun hold() {
        if (holders++ == 0 && installed()) client = context.contentResolver.acquireUnstableContentProviderClient(LinkContract.AUTHORITY)
    }

    fun release() {
        if (holders > 0 && --holders == 0) {
            client?.close()
            client = null
        }
    }

    /** Calls Ferb Link through the held client when there is one, else as usual. */
    private fun provider(method: String, arg: String?, extras: Bundle?): Bundle? {
        client?.let { held ->
            try {
                return held.call(method, arg, extras)
            } catch (e: DeadObjectException) {
                // Ferb Link died since: drop the client and call it afresh, which restarts it.
                held.close()
                client = context.contentResolver.acquireUnstableContentProviderClient(LinkContract.AUTHORITY)
            }
        }
        return context.contentResolver.call(LinkContract.AUTHORITY, method, arg, extras)
    }

    /**
     * Keeps the phone's Wi-Fi out of power save while a request is with the computer: with it on,
     * packets waited for the radio's next wake, about 1 s per request (ferb-link's pings: 0.1–3.3 s
     * in 500 ms steps). Android honours it only for the app in front with the screen on, so Ferb
     * holds it, not Ferb Link. Needs WAKE_LOCK, not INTERNET.
     */
    private val wifi by lazy {
        (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
            ?.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "ferb:link")?.apply { setReferenceCounted(true) }
    }

    /** Runs [work] with Wi-Fi in low latency (see [wifi]). */
    private inline fun <T> lowLatency(work: () -> T): T {
        val lock = wifi
        try { lock?.acquire() } catch (e: Exception) { }
        try {
            return work()
        } finally {
            try { if (lock?.isHeld == true) lock.release() } catch (e: Exception) { }
        }
    }

    /** Low-latency Wi-Fi for [ms] from now, e.g. while recording a spoken request, ahead of its transcription. */
    fun lowLatencyFor(ms: Long) {
        val lock = wifi ?: return
        try { lock.acquire() } catch (e: Exception) { return }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ try { if (lock.isHeld) lock.release() } catch (e: Exception) { } }, ms)
    }
    fun installed(): Boolean = try {
        context.packageManager.getPackageInfo(LinkContract.PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /** No network: whether Ferb Link is installed, on and set up. Quick; fine on the main thread. */
    override fun status(): LinkStatus {
        if (!installed()) return LinkStatus.NotInstalled
        val result = try {
            timed(LinkContract.METHOD_STATUS) { provider(LinkContract.METHOD_STATUS, null, null) }
        } catch (e: SecurityException) {
            return LinkStatus.NotAllowed
        } catch (e: Exception) {
            return LinkStatus.Failed(e.message ?: e.toString())
        } ?: return LinkStatus.Failed("Ferb Link didn't answer")
        return statusOf(result.values())
    }

    /**
     * Jev's pick among [toolsJson]'s tools (or "none") for [request], which selects the tool when
     * the tool selection step is Jev ([LinkPolicy.select]). Ferb Link gives up after
     * [JEV_TIMEOUT_MS]; never throws.
     */
    override suspend fun decide(request: String, toolsJson: String, timeoutMs: Int): JevReply = withContext(Dispatchers.IO) {
        val result = try {
            lowLatency { timed(LinkContract.METHOD_DECIDE) {
                provider(LinkContract.METHOD_DECIDE, null, bundleOf(
                    LinkContract.EXTRA_REQUEST to request,
                    LinkContract.EXTRA_TOOLS to toolsJson,
                    LinkContract.EXTRA_TIMEOUT_MS to timeoutMs,
                ))
            } }
        } catch (e: IllegalArgumentException) {
            return@withContext JevReply.failed("update Ferb Link", "This Ferb Link is too old for Jev")
        } catch (e: SecurityException) {
            return@withContext JevReply.failed("not allowed", "Ferb Link only answers apps signed with the same key")
        } catch (e: Exception) {
            return@withContext JevReply.failed("Ferb Link failed", "Ferb Link failed: ${e.message ?: e}")
        } ?: return@withContext JevReply.failed("no answer", "Ferb Link didn't answer")
        decisionOf(result.values())
    }

    /**
     * [request] rewritten by the computer's model ([LinkContract.METHOD_REWRITE]) with Ferb's own
     * prompt ([instructions], Gemini Nano's rewrite prompt, as the system message; at most
     * [maxTokens]): its answer comes back as [RewriteReply.raw], for Ferb to read as it reads Gemini
     * Nano's. An older Ferb Link ignores the prompt ([RewriteReply.instructed] false). Never throws:
     * a failure is a [RewriteReply] with its error set.
     */
    override suspend fun rewrite(request: String, instructions: String, maxTokens: Int, timeoutMs: Int): RewriteReply = withContext(Dispatchers.IO) {
        val result = try {
            lowLatency { timed(LinkContract.METHOD_REWRITE) {
                provider(LinkContract.METHOD_REWRITE, null, bundleOf(
                    LinkContract.EXTRA_REQUEST to request,
                    LinkContract.EXTRA_INSTRUCTIONS to instructions,
                    LinkContract.EXTRA_MAX_TOKENS to maxTokens,
                    LinkContract.EXTRA_TIMEOUT_MS to timeoutMs,
                ))
            } }
        } catch (e: IllegalArgumentException) {
            return@withContext RewriteReply.failed("update Ferb Link", "This Ferb Link is too old to rewrite requests")
        } catch (e: SecurityException) {
            return@withContext RewriteReply.failed("not allowed", "Ferb Link only answers apps signed with the same key")
        } catch (e: Exception) {
            return@withContext RewriteReply.failed("Ferb Link failed", "Ferb Link failed: ${e.message ?: e}")
        } ?: return@withContext RewriteReply.failed("no answer", "Ferb Link didn't answer")
        rewriteOf(result.values())
    }

    /**
     * Whether the computer answers now (Ferb Link checks its server, about 2 s at most), for the
     * settings page. Null when this Ferb Link is too old to check.
     */
    suspend fun check(): Reachability? = withContext(Dispatchers.IO) {
        val result = try {
            provider(LinkContract.METHOD_CHECK, null, null)
        } catch (e: IllegalArgumentException) {
            return@withContext null
        } catch (e: SecurityException) {
            return@withContext Reachability(false, null, "Ferb Link only answers apps signed with the same key")
        } catch (e: Exception) {
            return@withContext Reachability(false, null, e.message ?: e.toString())
        } ?: return@withContext Reachability(false, null, "Ferb Link didn't answer")
        reachabilityOf(result.values())
    }

    /**
     * Asks Ferb Link to load the computer's model if it's asleep or unloaded, when a request is
     * coming (the chat box tapped or recording started, with a step on the computer), so the wake (2 s, up to
     * 28 s when the model file has left the page cache) hides behind the typing or speech. Returns
     * at once; never throws.
     */
    suspend fun wake() {
        withContext(Dispatchers.IO) {
            try {
                lowLatencyFor(WAKE_LOW_LATENCY_MS)
                provider(LinkContract.METHOD_WAKE, null, null)
            } catch (e: Exception) {
                // An older Ferb Link, or none: the transcription just waits for the wake.
            }
        }
    }

    /**
     * The computer's transcription of [audio] (of type [mime]): what was heard, and the request as
     * a plain English command. Blocks until the computer answers or Ferb Link gives up; never
     * throws: a failure is a [Transcript] with [Transcript.error] set.
     */
    suspend fun transcribe(audio: ByteArray, mime: String, waking: Boolean = false): Transcript = withContext(Dispatchers.IO) {
        val result = try {
            lowLatency { timed(LinkContract.METHOD_TRANSCRIBE) {
                provider(LinkContract.METHOD_TRANSCRIBE, null, bundleOf(
                    LinkContract.EXTRA_AUDIO to audio,
                    LinkContract.EXTRA_AUDIO_MIME to mime,
                    LinkContract.EXTRA_TIMEOUT_MS to timeout(TRANSCRIBE_TIMEOUT_MS, waking),
                ))
            } }
        } catch (e: IllegalArgumentException) {
            return@withContext Transcript.failed("Update Ferb Link to use your voice")
        } catch (e: SecurityException) {
            return@withContext Transcript.failed("Ferb Link only answers apps signed with the same key")
        } catch (e: Exception) {
            return@withContext Transcript.failed("Ferb Link failed: ${e.message ?: e}")
        } ?: return@withContext Transcript.failed("Ferb Link didn't answer")
        transcriptOf(result.values())
    }

    /**
     * Logs how long each call to Ferb Link took (`adb logcat -s FerbLink`), with logcat's wall-clock
     * timestamps to line up with Ferb Link's and the server's logs.
     */
    private inline fun <T> timed(method: String, call: () -> T): T {
        val started = SystemClock.elapsedRealtime()
        Log.i(TAG, "$method: start")
        try {
            return call()
        } finally {
            Log.i(TAG, "$method: ${SystemClock.elapsedRealtime() - started} ms")
        }
    }

    /** Ferb Link's own screen, where its server is set up and switched on. */
    fun openIntent(): Intent? = context.packageManager.getLaunchIntentForPackage(LinkContract.PACKAGE)

    companion object {
        /** A `status` result. */
        fun statusOf(result: LinkResult): LinkStatus {
            val version = result.int(LinkContract.RESULT_VERSION)
            return when {
                version != LinkContract.VERSION -> LinkStatus.Mismatch(version)
                !result.bool(LinkContract.RESULT_ENABLED) -> LinkStatus.Off
                !result.bool(LinkContract.RESULT_CONFIGURED) -> LinkStatus.NotSetUp
                else -> LinkStatus.Ready(result.text(LinkContract.RESULT_MODEL), result.bool(LinkContract.RESULT_AUDIO),
                    asleep = asleep(result.text(LinkContract.RESULT_MODEL_STATE)), jev = result.bool(LinkContract.RESULT_JEV),
                    jevCapped = result.text(LinkContract.RESULT_JEV_OFF_REASON) == "cap")
            }
        }

        /** A `decide` result. */
        fun decisionOf(result: LinkResult): JevReply {
            val version = result.int(LinkContract.RESULT_VERSION)
            if (version != LinkContract.VERSION) return JevReply.failed("versions differ", "Ferb Link speaks version $version, Ferb version ${LinkContract.VERSION}: update the older one")
            return JevReply.parse(result.text(LinkContract.RESULT_REPLY) ?: return JevReply.failed("unexpected answer", "Ferb Link sent no reply"))
        }

        /** A `rewrite` result. */
        fun rewriteOf(result: LinkResult): RewriteReply {
            val version = result.int(LinkContract.RESULT_VERSION)
            if (version != LinkContract.VERSION) return RewriteReply.failed("versions differ", "Ferb Link speaks version $version, Ferb version ${LinkContract.VERSION}: update the older one")
            return RewriteReply.parse(result.text(LinkContract.RESULT_REPLY) ?: return RewriteReply.failed("unexpected answer", "Ferb Link sent no reply"))
        }

        /** A `check` result. */
        fun reachabilityOf(result: LinkResult) = Reachability(
            reachable = result.bool(LinkContract.RESULT_REACHABLE),
            model = result.text(LinkContract.RESULT_MODEL),
            error = result.text(LinkContract.RESULT_ERROR),
            audio = result.bool(LinkContract.RESULT_AUDIO),
            errorDetail = result.text(LinkContract.RESULT_ERROR_DETAIL),
        )

        /** A `transcribe` result. */
        fun transcriptOf(result: LinkResult): Transcript {
            val version = result.int(LinkContract.RESULT_VERSION)
            if (version != LinkContract.VERSION) return Transcript.failed("Ferb Link speaks version $version, Ferb version ${LinkContract.VERSION}: update the older one")
            return Transcript.parse(result.text(LinkContract.RESULT_REPLY) ?: return Transcript.failed("Ferb Link sent no reply"))
        }

        /**
         * Ferb Link's reply, shaped like Needle 3's. The computer's model has no calibrated
         * confidence like Needle 3's, and no stand-in for one held back every wrong call in Ferb's
         * bench (Gemma 4 E2B's first token: 20 wrong calls even at 1.00), so whether its answer
         * runs doesn't depend on one ([LinkPolicy.verdict]). The first token's probability is kept
         * as the confidence, for the details.
         */
        fun parse(json: String): NeedleReply = try {
            val reply = NeedleReply.parse(json)
            reply.copy(confidence = reply.first ?: reply.confidence, source = reply.source ?: SOURCE, calls = timedCalls(json, reply.calls))
        } catch (e: Exception) {
            failed("unexpected answer", "Ferb Link's reply couldn't be read: ${e.message}")
        }

        /**
         * A timed reply's per-call extras ([LinkContract.EXTRA_TIME_FORMAT]) in the calls' arguments:
         * `when` for [TimeArgs], and Ferb Link's `asks` or a `trimmed` argument as a reason to ask.
         */
        private fun timedCalls(json: String, calls: List<ToolCall>): List<ToolCall> {
            val raw = JSONObject(json).optJSONArray("function_calls") ?: return calls
            return calls.mapIndexed { i, call ->
                val o = raw.optJSONObject(i) ?: return@mapIndexed call
                val extra = mutableMapOf<String, Any?>()
                o.optJSONObject("when")?.let { extra["when"] = it }
                val asks = o.optJSONArray("asks")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
                when {
                    asks.isNotEmpty() -> extra[TimeArgs.ASK] = asks.first()
                    o.optBoolean("trimmed") -> extra[TimeArgs.ASK] = "a value from your computer was cut short"
                }
                if (extra.isEmpty()) call else call.copy(arguments = call.arguments + extra)
            }
        }

        const val SOURCE = "laptop"
        private const val TAG = "FerbLink"
        /** After a wake (the box tapped, the mic started), how long Wi-Fi stays low latency for the request to come. */
        private const val WAKE_LOW_LATENCY_MS = 30_000L

        /**
         * How long Ferb waits for the computer: Gemma 4 E2B takes 0.26 s (median) and 0.54 s (90th
         * percentile) per request on the laptop's GPU, and about 2 s waking after 5 idle minutes. A
         * load from a cold disk (10–30 s) times out; Needle 3 answers that one, and the next is fast.
         */
        const val TIMEOUT_MS = 10_000

        /** For the timed call ([LinkContract.EXTRA_TIME_FORMAT]): 1.3 s median and 1.5 s at the 90th on the bench (2026-10-19). */
        const val TIMED_TIMEOUT_MS = 6_000

        /**
         * How long Ferb waits for a transcription: Gemma 4 E2B took 0.5–1 s for up to 11 s of
         * speech and 1.9 s for 30 s on the laptop's GPU, plus a wake of about 2 s (once 12 s) if
         * `wake` hasn't finished.
         */
        const val TRANSCRIBE_TIMEOUT_MS = 15_000

        /**
         * While the computer's model loads (from asleep or unloaded): a cold load took about 30 s on
         * the laptop (2026-10-15) and timed out the request, so Ferb waits the longest it allows.
         */
        const val WAKING_TIMEOUT_MS = 60_000

        /**
         * How long Jev may take: 260–350 ms in ferb-link's smoke run and 286 ms (median) in Ferb's
         * bench (2026-10-15), 670 ms on a new connection; every request waits for it, so after
         * this the request goes on without Jev.
         */
        const val JEV_TIMEOUT_MS = 6_000

        /** How long the rewrite may take: 0.26 s (median) and 0.35 s (90th percentile) on the laptop's GPU, 2026-10-16. */
        const val REWRITE_TIMEOUT_MS = 10_000

        /** The timeout for a request: [normal], or [WAKING_TIMEOUT_MS] while the model is [waking]. */
        fun timeout(normal: Int, waking: Boolean) = if (waking) WAKING_TIMEOUT_MS else normal

        /** Whether a `model_state` means the model has to load first. */
        fun asleep(state: String?) = state == "asleep" || state == "unloaded"

        /** A failure: [reason] in a few words, for the chat, and at length in [detail], for the details. */
        private fun failed(reason: String, detail: String) = NeedleReply(
            calls = emptyList(), suppressed = emptyList(), reasoning = "", confidence = null, ungrounded = emptyList(),
            negation = false, prefillTps = 0.0, decodeTps = 0.0, peakRamMb = 0.0, error = reason, source = SOURCE, errorDetail = detail,
        )
    }
}

/**
 * A result from Ferb Link (its Bundle's values by key), so the parsing above runs without
 * Android: the contract tests replay Ferb Link's fixtures through it.
 */
fun interface LinkResult {
    operator fun get(key: String): Any?

    fun int(key: String) = (get(key) as? Number)?.toInt() ?: -1
    fun bool(key: String) = get(key) as? Boolean ?: false
    /** A string, null when missing or blank. */
    fun text(key: String) = (get(key) as? String)?.ifBlank { null }
}

@Suppress("DEPRECATION")
private fun Bundle.values() = LinkResult { get(it) }

/** What Ferb Link's `check` found: whether the computer answers, its model, or why not. */
data class Reachability(val reachable: Boolean, val model: String?, val error: String?, val audio: Boolean = false, val errorDetail: String? = null)

/** [status] in a few words, for the settings page and for notes under answers. */
fun describe(status: LinkStatus): String = when (status) {
    LinkStatus.NotInstalled -> "Ferb Link isn't installed"
    is LinkStatus.Mismatch -> "Ferb Link and Ferb don't match (version ${status.version}, Ferb ${LinkContract.VERSION}): update the older one"
    LinkStatus.NotAllowed -> "Ferb Link answers only a Ferb from the same place: install both from Releases, or build both on one computer"
    LinkStatus.Off -> "Ferb Link is off: switch on “Answer Ferb's requests” in it"
    LinkStatus.NotSetUp -> "Ferb Link has no computer set up yet"
    is LinkStatus.Ready -> status.model?.let { "Connected \u00b7 $it" } ?: "Connected"
    is LinkStatus.Failed -> "Ferb Link failed: ${status.reason}"
}

/** Why Ferb Link can't answer, in a few words like Ferb Link's own short errors, for the chat. */
fun brief(status: LinkStatus): String = when (status) {
    LinkStatus.NotInstalled -> "not installed"
    is LinkStatus.Mismatch -> "versions differ"
    LinkStatus.NotAllowed -> "not allowed"
    LinkStatus.Off -> "turned off"
    LinkStatus.NotSetUp -> "not set up"
    is LinkStatus.Ready -> "ready"
    is LinkStatus.Failed -> "Ferb Link failed"
}

/**
 * A recording as the computer heard it: [heard], the words said (Hindi in Latin letters, as
 * Hinglish is typed), and [text], the same request as a plain English command, which Ferb answers
 * (its keywords, dates and Needle 3 need English). The same when the speech was English.
 */
data class Transcript(val heard: String, val text: String, val model: String? = null, val ms: Long? = null, val error: String? = null) {
    companion object {
        fun parse(json: String): Transcript = try {
            val o = JSONObject(json)
            val error = o.optString("error").ifBlank { null }
            Transcript(
                heard = o.optString("heard").trim(),
                text = o.optString("text").trim(),
                model = o.optString("model").ifBlank { null },
                ms = if (o.has("ms")) o.optLong("ms") else null,
                error = error,
            )
        } catch (e: Exception) {
            failed("Ferb Link's reply couldn't be read: ${e.message}")
        }

        fun failed(reason: String) = Transcript("", "", error = reason)
    }
}

/**
 * Jev's answer to `decide`: [choice], a tool's name or "none", with [probabilities] for every option
 * it was asked; [error] (a few words), [errorDetail] and [errorKind] when it failed.
 */
data class JevReply(
    val choice: String?,
    val probabilities: Map<String, Double>,
    val model: String? = null,
    val ms: Long? = null,
    val error: String? = null,
    val errorDetail: String? = null,
    val errorKind: String? = null,
) {
    /** The probability of [choice], when Jev answered. */
    val p: Double? get() = if (error == null) choice?.let { probabilities[it] } else null

    companion object {
        const val NONE = "none"

        fun parse(json: String): JevReply = try {
            val o = JSONObject(json)
            val probabilities = o.optJSONObject("probabilities")?.let { p -> p.keys().asSequence().associateWith { p.getDouble(it) } } ?: emptyMap()
            val success = o.optBoolean("success", false)
            JevReply(
                choice = o.optString("choice").ifBlank { null },
                probabilities = probabilities,
                model = o.optString("model").ifBlank { null },
                ms = if (o.has("ms")) o.optLong("ms") else null,
                error = if (success) null else o.optString("error").ifBlank { "Jev failed" },
                errorDetail = o.optString("error_detail").ifBlank { null },
                errorKind = o.optString("error_kind").ifBlank { null },
            )
        } catch (e: Exception) {
            failed("unexpected answer", "Jev's reply couldn't be read: ${e.message}")
        }

        fun failed(reason: String, detail: String) = JevReply(null, emptyMap(), error = reason, errorDetail = detail)
    }
}

/**
 * The computer's rewrite of a request ([LinkContract.METHOD_REWRITE]): [commands] in plain English,
 * none when it asks for nothing; [invented], facts in them the request didn't hold (Ferb runs none
 * of those without asking); [error] (a few words) and [errorDetail] when it failed.
 */
data class RewriteReply(
    val commands: List<String>,
    val invented: List<String> = emptyList(),
    /** The model's own text, with Ferb's prompt ([instructed]): Ferb reads it as Gemini Nano's. */
    val raw: String? = null,
    /** Whether Ferb Link used Ferb's prompt; an older one answers with its own and doesn't say so. */
    val instructed: Boolean = false,
    val model: String? = null,
    val ms: Long? = null,
    val error: String? = null,
    val errorDetail: String? = null,
    val errorKind: String? = null,
) {
    companion object {
        fun parse(json: String): RewriteReply = try {
            val o = JSONObject(json)
            fun strings(key: String) = o.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.get(it).toString() } }.orEmpty()
            RewriteReply(
                commands = strings("commands").map { it.trim() }.filter { it.isNotEmpty() },
                invented = strings("invented"),
                raw = if (o.has("raw")) o.optString("raw") else null,
                instructed = o.optBoolean("instructions", false),
                model = o.optString("model").ifBlank { null },
                ms = if (o.has("ms")) o.optLong("ms") else null,
                error = if (o.optBoolean("success", false)) null else o.optString("error").ifBlank { "rewrite failed" },
                errorDetail = o.optString("error_detail").ifBlank { null },
                errorKind = o.optString("error_kind").ifBlank { null },
            )
        } catch (e: Exception) {
            failed("unexpected answer", "The rewrite couldn't be read: ${e.message}")
        }

        fun failed(reason: String, detail: String) = RewriteReply(emptyList(), error = reason, errorDetail = detail)
    }
}

/** What answering needs from Ferb Link ([FerbLink]), so [dev.abhay.assistant.ui.AnswerFlow] runs in tests with a fake. */
interface LinkCalls {
    fun status(): LinkStatus
    suspend fun rewrite(request: String, instructions: String, maxTokens: Int, timeoutMs: Int = FerbLink.REWRITE_TIMEOUT_MS): RewriteReply
    suspend fun decide(request: String, toolsJson: String, timeoutMs: Int = FerbLink.JEV_TIMEOUT_MS): JevReply
}

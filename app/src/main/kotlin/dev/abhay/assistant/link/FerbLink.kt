package dev.abhay.assistant.link

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.core.os.bundleOf
import dev.abhay.assistant.engine.NeedleReply
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

    const val EXTRA_REQUEST = "request"
    const val EXTRA_TOOLS = "tools"
    /** How long Ferb Link may wait for the computer (1–60 s); an older Ferb Link ignores it and waits 30 s. */
    const val EXTRA_TIMEOUT_MS = "timeout_ms"

    const val RESULT_VERSION = "version"
    const val RESULT_ENABLED = "enabled"
    const val RESULT_CONFIGURED = "configured"
    const val RESULT_REPLY = "reply"
    /** In `status` and `check`: the model the computer runs, last seen; empty or missing when unknown. */
    const val RESULT_MODEL = "model"
    /** In `check`. */
    const val RESULT_REACHABLE = "reachable"
    const val RESULT_ERROR = "error"
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
    /** [model]: the computer's model as Ferb Link last saw it, when it says. */
    data class Ready(val model: String? = null) : LinkStatus
    data class Failed(val reason: String) : LinkStatus
}

/**
 * Ferb Link, the companion app that holds the INTERNET permission Ferb must never have and passes
 * requests to a bigger model on the user's own computer (over Tailscale). Ferb talks to it through
 * one `ContentResolver.call`, so Ferb itself stays offline; Needle 3 answers whenever Ferb Link
 * can't.
 */
class FerbLink(private val context: Context) {
    fun installed(): Boolean = try {
        context.packageManager.getPackageInfo(LinkContract.PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /** No network: whether Ferb Link is installed, on and set up. Quick; fine on the main thread. */
    fun status(): LinkStatus {
        if (!installed()) return LinkStatus.NotInstalled
        val result = try {
            context.contentResolver.call(LinkContract.AUTHORITY, LinkContract.METHOD_STATUS, null, null)
        } catch (e: SecurityException) {
            return LinkStatus.NotAllowed
        } catch (e: Exception) {
            return LinkStatus.Failed(e.message ?: e.toString())
        } ?: return LinkStatus.Failed("Ferb Link didn't answer")
        return statusOf(result)
    }

    /**
     * Asks the computer's model, with the same [toolsJson] Needle 3 would get. Blocks on the network
     * until the computer answers or Ferb Link gives up, so it runs off the main thread. Never throws:
     * a failure is a reply with [NeedleReply.error] set, and Needle 3 answers instead.
     */
    suspend fun complete(request: String, toolsJson: String): NeedleReply = withContext(Dispatchers.IO) {
        val result = try {
            context.contentResolver.call(LinkContract.AUTHORITY, LinkContract.METHOD_COMPLETE, null, bundleOf(
                LinkContract.EXTRA_REQUEST to request,
                LinkContract.EXTRA_TOOLS to toolsJson,
                LinkContract.EXTRA_TIMEOUT_MS to TIMEOUT_MS,
            ))
        } catch (e: SecurityException) {
            return@withContext failed("Ferb Link only answers apps signed with the same key")
        } catch (e: Exception) {
            return@withContext failed(if (installed()) "Ferb Link failed: ${e.message ?: e}" else "Ferb Link isn't installed")
        } ?: return@withContext failed("Ferb Link didn't answer")
        replyOf(result)
    }

    /**
     * Whether the computer answers now (Ferb Link checks its server, about 2 s at most), for the
     * settings page. Null when this Ferb Link is too old to check.
     */
    suspend fun check(): Reachability? = withContext(Dispatchers.IO) {
        val result = try {
            context.contentResolver.call(LinkContract.AUTHORITY, LinkContract.METHOD_CHECK, null, null)
        } catch (e: IllegalArgumentException) {
            return@withContext null
        } catch (e: SecurityException) {
            return@withContext Reachability(false, null, "Ferb Link only answers apps signed with the same key")
        } catch (e: Exception) {
            return@withContext Reachability(false, null, e.message ?: e.toString())
        } ?: return@withContext Reachability(false, null, "Ferb Link didn't answer")
        Reachability(
            reachable = result.getBoolean(LinkContract.RESULT_REACHABLE),
            model = result.getString(LinkContract.RESULT_MODEL)?.ifBlank { null },
            error = result.getString(LinkContract.RESULT_ERROR)?.ifBlank { null },
        )
    }

    /** Ferb Link's own screen, where its server is set up and switched on. */
    fun openIntent(): Intent? = context.packageManager.getLaunchIntentForPackage(LinkContract.PACKAGE)

    companion object {
        fun statusOf(result: Bundle): LinkStatus {
            val version = result.getInt(LinkContract.RESULT_VERSION, -1)
            return when {
                version != LinkContract.VERSION -> LinkStatus.Mismatch(version)
                !result.getBoolean(LinkContract.RESULT_ENABLED) -> LinkStatus.Off
                !result.getBoolean(LinkContract.RESULT_CONFIGURED) -> LinkStatus.NotSetUp
                else -> LinkStatus.Ready(result.getString(LinkContract.RESULT_MODEL)?.ifBlank { null })
            }
        }

        fun replyOf(result: Bundle): NeedleReply {
            val version = result.getInt(LinkContract.RESULT_VERSION, -1)
            if (version != LinkContract.VERSION) return failed("Ferb Link speaks version $version, Ferb version ${LinkContract.VERSION}: update the older one")
            val json = result.getString(LinkContract.RESULT_REPLY) ?: return failed("Ferb Link sent no reply")
            return parse(json)
        }

        /**
         * Ferb Link's reply, shaped like Needle 3's. The computer's model has no calibrated
         * confidence like Needle 3's, and no stand-in for one held back every wrong call in Ferb's
         * bench (Gemma 4 E2B's first token: 20 wrong calls even at 1.00), so whether its answer
         * runs doesn't depend on one ([LinkPolicy.agrees]). The first token's probability is kept
         * as the confidence, for the details.
         */
        fun parse(json: String): NeedleReply = try {
            val reply = NeedleReply.parse(json)
            reply.copy(confidence = reply.first ?: reply.confidence, source = reply.source ?: SOURCE)
        } catch (e: Exception) {
            failed("Ferb Link's reply couldn't be read: ${e.message}")
        }

        const val SOURCE = "laptop"

        /**
         * How long Ferb waits for the computer: Gemma 4 E2B took 1.5 s (median) and 2.6 s (90th
         * percentile) per request on the laptop's CPU in the bench.
         */
        const val TIMEOUT_MS = 10_000

        private fun failed(reason: String) = NeedleReply(
            calls = emptyList(), suppressed = emptyList(), reasoning = "", confidence = null, ungrounded = emptyList(),
            negation = false, prefillTps = 0.0, decodeTps = 0.0, peakRamMb = 0.0, error = reason, source = SOURCE,
        )
    }
}

/** What Ferb Link's `check` found: whether the computer answers, its model, or why not. */
data class Reachability(val reachable: Boolean, val model: String?, val error: String?)

/** [status] in a few words, for the settings page and for notes under answers. */
fun describe(status: LinkStatus): String = when (status) {
    LinkStatus.NotInstalled -> "Ferb Link isn't installed"
    is LinkStatus.Mismatch -> "Ferb Link and Ferb don't match (version ${status.version}, Ferb ${LinkContract.VERSION}): update the older one"
    LinkStatus.NotAllowed -> "Ferb Link only answers apps signed with the same key"
    LinkStatus.Off -> "Ferb Link is off: switch on “Answer Ferb's requests” in it"
    LinkStatus.NotSetUp -> "Ferb Link has no computer set up yet"
    is LinkStatus.Ready -> status.model?.let { "Ready: $it" } ?: "Ready"
    is LinkStatus.Failed -> "Ferb Link failed: ${status.reason}"
}

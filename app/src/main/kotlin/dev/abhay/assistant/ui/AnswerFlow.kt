package dev.abhay.assistant.ui

import dev.abhay.assistant.engine.NeedleReply
import dev.abhay.assistant.link.JevPick
import dev.abhay.assistant.link.JevReply
import dev.abhay.assistant.link.LinkCalls
import dev.abhay.assistant.link.LinkPolicy
import dev.abhay.assistant.link.LinkStatus
import dev.abhay.assistant.link.brief
import dev.abhay.assistant.pipeline.Fallback
import dev.abhay.assistant.pipeline.Pipeline
import dev.abhay.assistant.pipeline.RewriteStep
import dev.abhay.assistant.pipeline.ToolStep
import dev.abhay.assistant.tools.TimeArgs
import dev.abhay.assistant.tools.Tool
import dev.abhay.assistant.tools.ToolConfig
import dev.abhay.assistant.tools.ToolRouter
import dev.abhay.assistant.tools.Tools
import dev.abhay.assistant.tools.Verdict
import dev.abhay.assistant.voice.GeminiRewrite
import dev.abhay.assistant.voice.Rewritten
import java.time.ZonedDateTime
import java.util.Locale
import kotlinx.coroutines.CancellationException

/**
 * One step a request took, recorded as it ran, for the details' path ("Voice: Gemini Nano →
 * Rewrite: Gemini Nano → Needle 3 (0.81)"): [name], and [detail] such as a pick and its probability.
 */
data class Step(val name: String, val detail: String? = null) {
    val text get() = if (detail == null) name else "$name ($detail)"

    companion object {
        fun chain(steps: List<Step>) = steps.joinToString(" → ") { it.text }
    }
}

/**
 * Ferb's answer to a request before anything runs: [shown], the reply whose calls are offered,
 * read from [command] (or the request when that's none); the decision; and how it got there
 * ([path], with [fallbacks] for the steps that couldn't be used this time).
 */
data class Answer(
    val shown: NeedleReply,
    val verdict: Verdict,
    /** The tools Needle 3 was given (empty: every tool turned on). */
    val checked: List<String>,
    val path: List<Step>,
    /** The request as rewritten, when that changed it; null otherwise. */
    val command: String? = null,
    /** The rewrite said the request asks the phone to do nothing (NONE). */
    val nothingAsked: Boolean = false,
    val fallbacks: List<Fallback> = emptyList(),
    val jev: JevReply? = null,
)

/**
 * A request through the steps the user set (Settings → How Ferb understands you): the rewrite into
 * one plain English command (Gemini Nano on the phone, or Gemma 4 on the computer through Ferb Link,
 * with the same prompt, [GeminiRewrite]), the tool selection (Jev, through Ferb Link), then Needle 3,
 * which always makes the call. Hearing comes before, in the view model. A Ferb-Link step that fails
 * falls back for this request (the rewrite to Gemini Nano when it can be used, else the words as
 * they are; the tool selection to none), and a failed Gemini Nano rewrite to the words as they are;
 * [Answer.fallbacks] says so. Every step is recorded as it runs ([Step]).
 */
class AnswerFlow(
    private val link: LinkCalls,
    private val needle: suspend (request: String, tools: List<Tool>) -> NeedleReply,
    /** Gemini Nano's rewrite; throws when it can't. */
    private val gemini: suspend (String) -> Rewritten,
    private val asking: (String?) -> Unit = {},
    private val now: () -> ZonedDateTime = ZonedDateTime::now,
) {
    /**
     * [said]: typed, or the words a spoken request was heard as; [heardBy]: the hearing step(s),
     * null for a typed one. [geminiUsable]: Gemini Nano's rewrite can stand in for Ferb Link's.
     */
    suspend fun answer(
        said: String,
        enabled: List<Tool>,
        configs: Map<String, ToolConfig>,
        pipeline: Pipeline,
        heardBy: List<Step>? = null,
        geminiUsable: Boolean = false,
    ): Answer {
        val path = (heardBy ?: listOf(Step("Typed"))).toMutableList()
        val fallbacks = mutableListOf<Fallback>()

        // The rewrite: one command, or NONE.
        var rewritten: Rewritten? = null
        suspend fun viaGemini(instead: Boolean = false) {
            asking("Gemini Nano is rewriting it…")
            rewritten = try {
                gemini(said).also { path += Step(if (instead) "Rewrite: Gemini Nano instead" else "Rewrite: Gemini Nano") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fallbacks += Fallback("Rewrite", "your words as they are", "Gemini Nano: ${e.message ?: e}")
                path += Step("Rewrite: off (Gemini Nano failed)")
                null
            }
        }
        suspend fun linkFailed(why: String) {
            if (geminiUsable) {
                fallbacks += Fallback("Rewrite", "Gemini Nano", "Ferb Link: $why")
                viaGemini(instead = true)
            } else {
                fallbacks += Fallback("Rewrite", "your words as they are", "Ferb Link: $why")
                path += Step("Rewrite: off (Ferb Link: $why)")
            }
        }
        when (pipeline.rewrite) {
            RewriteStep.Off -> {}
            RewriteStep.Gemini -> viaGemini()
            RewriteStep.Link -> when (val status = link.status()) {
                is LinkStatus.Ready -> {
                    asking(if (status.asleep) "Waking your computer…" else "Your computer is rewriting it…")
                    val reply = link.rewrite(said, GeminiRewrite.PROMPT, GeminiRewrite.MAX_TOKENS)
                    val raw = reply.raw
                    val error = reply.error
                    when {
                        error != null -> linkFailed(error)
                        // An older Ferb Link answered with its own prompt: not this step's rewrite.
                        !reply.instructed || raw == null -> linkFailed("too old, update it")
                        else -> runCatching { GeminiRewrite.parse(said, raw) }
                            .onSuccess { rewritten = it; path += Step("Rewrite: Ferb Link", reply.ms?.let { ms -> "$ms ms" }) }
                            .onFailure { linkFailed("unreadable answer") }
                    }
                }
                else -> linkFailed(brief(status))
            }
        }
        if (rewritten != null && rewritten?.command == null) {
            path += Step("Nothing for the phone to do")
            return Answer(NO_TOOL, Verdict.of(NO_TOOL, configs, said), emptyList(), path, nothingAsked = true, fallbacks = fallbacks)
        }
        val command = rewritten?.command
        val text = command ?: said

        // The tool selection: Jev narrows the tools to its pick.
        val routed = ToolRouter.route(text, enabled, configs)
        var needleTools = routed
        var jev: JevReply? = null
        if (pipeline.tools == ToolStep.Link && enabled.isNotEmpty()) {
            val status = link.status()
            when {
                status !is LinkStatus.Ready -> {
                    fallbacks += Fallback("Tool selection", "Needle 3 alone", "Ferb Link: ${brief(status)}")
                    path += Step("Tool selection: off (Ferb Link: ${brief(status)})")
                }
                !status.jev -> {
                    val why = if (status.jevCapped) "Jev reached its spending limit: raise it in Ferb Link" else "Jev is off in Ferb Link"
                    fallbacks += Fallback("Tool selection", "Needle 3 alone", why)
                    path += Step("Tool selection: off (${if (status.jevCapped) "Jev's limit reached" else "Jev off"})")
                }
                else -> {
                    asking("Asking Jev…")
                    val reply = link.decide(text, Tools.json(routed.ifEmpty { enabled }))
                    jev = reply
                    when (val pick = LinkPolicy.select(reply, enabled.map { it.name })) {
                        is JevPick.Tool -> {
                            needleTools = listOfNotNull(Tools.byName(pick.name))
                            path += Step("Tool selection: Jev", pick(reply))
                        }
                        JevPick.NoTool -> {
                            path += Step("Tool selection: Jev", pick(reply))
                            return Answer(NO_TOOL, Verdict.of(NO_TOOL, configs, text), emptyList(), path, command = command.differentFrom(said), fallbacks = fallbacks, jev = reply)
                        }
                        is JevPick.Fallback -> {
                            fallbacks += Fallback("Tool selection", "Needle 3 alone", pick.detail)
                            path += Step("Tool selection: off (${pick.reason})")
                        }
                    }
                }
            }
        }

        // Needle 3 makes the call; its times are read from the command, and checked against what was said.
        asking(null)
        val reply = askNeedle(text, enabled, needleTools)
        if (enabled.isNotEmpty()) path += needleStep(reply)
        val texts = listOf(text, said).distinct()
        val shown = reply.copy(calls = reply.calls.map { call -> call.copy(arguments = TimeArgs.apply(call.name, call.arguments, texts, now())) })
        return Answer(
            shown = shown,
            verdict = Verdict.of(shown, configs, text),
            checked = needleTools.map { it.name },
            path = path,
            command = command.differentFrom(said),
            fallbacks = fallbacks,
            jev = jev,
        )
    }

    private fun String?.differentFrom(said: String) = this?.takeUnless { it.equals(said.trim(), ignoreCase = true) }

    private suspend fun askNeedle(request: String, enabled: List<Tool>, routed: List<Tool>): NeedleReply = when {
        routed.isNotEmpty() -> needle(request, routed)
        enabled.isEmpty() -> NO_TOOL
        else -> needle(request, enabled)
    }

    companion object {
        /** The answer when no tool is on, Jev says none fits, or the rewrite says nothing is asked. */
        val NO_TOOL = NeedleReply(
            calls = emptyList(), suppressed = emptyList(), reasoning = "", confidence = null, ungrounded = emptyList(),
            negation = false, prefillTps = 0.0, decodeTps = 0.0, peakRamMb = 0.0, error = null,
        )

        /** Jev's pick for the path: "Alarm, 0.93", or "no tool, 0.80". */
        fun pick(jev: JevReply) = String.format(Locale.US, "%s, %.2f", jev.choice?.let { Tools.byName(it)?.title } ?: "no tool", jev.p ?: 0.0)

        /** Needle 3's step: its confidence, or why it failed. */
        fun needleStep(reply: NeedleReply) = when {
            reply.error != null -> Step("Needle 3: ${reply.error}")
            else -> Step("Needle 3", reply.confidence?.let { String.format(Locale.US, "%.2f", it) })
        }
    }
}

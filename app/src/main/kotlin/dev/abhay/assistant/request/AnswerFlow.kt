package dev.abhay.assistant.request

import dev.abhay.assistant.engine.Decision
import dev.abhay.assistant.engine.NeedleReply
import dev.abhay.assistant.engine.ToolCall
import dev.abhay.assistant.link.FerbLink
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
import dev.abhay.assistant.tools.ContentMap
import dev.abhay.assistant.tools.DateGate
import dev.abhay.assistant.tools.SaidNumbers
import dev.abhay.assistant.tools.Tool
import dev.abhay.assistant.tools.ToolConfig
import dev.abhay.assistant.tools.ToolRouter
import dev.abhay.assistant.tools.Tools
import dev.abhay.assistant.tools.Verdict
import dev.abhay.assistant.voice.ContentRewrite
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
    /** Why Ferb asks before running the calls, from the content fix's guards ([AnswerFlow.Guard]); empty when none does. */
    val asks: List<String> = emptyList(),
    /** For a refusal, which step refused and why, in no words of the user's (for the log); null otherwise. */
    val refused: String? = null,
)

/**
 * A request through the steps the user set (Settings → How Ferb understands you): the rewrite into
 * one plain English command (Gemini Nano on the phone, or Gemma 4 on the computer through Ferb Link,
 * with the same prompt, [ContentRewrite]), the tool selection (Jev, through Ferb Link), then Needle 3,
 * which always makes the call. A rewritten request keeps the words to write down or send as said:
 * the rewrite quotes them, Needle 3 sees them in Latin letters and the router not at all, and the
 * content argument is mapped back to the words as said ([ContentMap]); guards ask first when it
 * can't be, or when the words as said name another tool or give a time the command doesn't ([Guard]). Hearing comes before, in the view model. A Ferb-Link step that fails
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
    private val content: ContentMap = ContentMap.android(),
    /** The keyword router, and Ferb's date gate for a call: injected so tests can see each runs once per text or call. */
    private val route: (String, List<Tool>, Map<String, ToolConfig>) -> List<Tool> = ToolRouter::route,
    private val gate: (String, Map<String, Any?>, String, String?, ZonedDateTime, String?) -> DateGate.Gated = DateGate::apply,
) {
    /** Why the content fix's guards ask first (0.2, as frozen in build/content/frozen-2). */
    enum class Guard(val why: String) {
        /** Neither the content argument nor the command's quote is in the words as said. */
        NotSaid("the words to keep aren't in what you said"),
        /** The words as said name tools, but not the one called ("note: call Ravi" called). */
        OtherTool("what you said names another action"),
        /** The words as said give a time and the command none (it was dropped). */
        TimeDropped("what you said gives a time the command doesn't"),
    }

    /**
     * [said]: typed, or the words a spoken request was heard as; [heardBy]: the hearing step(s),
     * null for a typed one. [geminiUsable]: Gemini Nano's rewrite can stand in for Ferb Link's.
     * [linkStatus]: Ferb Link's status, as the caller has just read it, off the main thread (a call into
     * another app), once for the request.
     */
    suspend fun answer(
        said: String,
        enabled: List<Tool>,
        configs: Map<String, ToolConfig>,
        pipeline: Pipeline,
        heardBy: List<Step>? = null,
        geminiUsable: Boolean = false,
        linkStatus: LinkStatus,
    ): Answer {
        val trail = Trail(heardBy ?: listOf(Step("Typed")))
        val status = linkStatus

        val rewritten = rewrite(said, pipeline.rewrite, geminiUsable, status, trail)
        if (rewritten != null && rewritten.command == null) {
            trail.path += Step("Nothing for the phone to do")
            return Answer(NO_TOOL, Verdict.of(NO_TOOL, configs, said), emptyList(), trail.path, nothingAsked = true, fallbacks = trail.fallbacks,
                refused = "rewrite: nothing for the phone to do")
        }
        val command = rewritten?.command
        // Needle 3 reads the command with its quoted words in Latin letters; the router never reads them.
        val text = command?.let(content::forNeedle) ?: said
        val routedBy = command?.let { ContentMap.masked(text) } ?: text
        // Routed once per text: the text Needle 3 gets, and the words as said when they differ.
        val masked = route(routedBy, enabled, configs)
        val saidRouted = if (routedBy == said) masked else route(said, enabled, configs)
        // A quote can hold the only word that names the tool ("Add \"Meeting with …\" Tuesday 3 to 4 pm",
        // 2026-11-15): when the command names none, the words as said route it.
        val routed = masked.ifEmpty { saidRouted }

        val selection = select(text, routed, enabled, pipeline.tools, status, trail)
        if (selection is Selection.NoTool) {
            return Answer(NO_TOOL, Verdict.of(NO_TOOL, configs, text), emptyList(), trail.path, command = command.differentFrom(said), fallbacks = trail.fallbacks, jev = selection.jev,
                refused = "Jev: no tool fits")
        }
        selection as Selection.Tools

        // Needle 3 makes the call; its times are read from the command, and checked against what was said.
        asking(null)
        val reply = askNeedle(text, enabled, selection.tools)
        if (enabled.isNotEmpty()) trail.path += needleStep(reply)
        // One clock for the request: the time guard and every timed call read the same day.
        val guarded = guard(reply, said, command, routed, saidRouted, now(), trail)
        val verdict = decide(guarded, configs, text, said, command)
        return Answer(
            shown = guarded.shown,
            verdict = verdict,
            checked = selection.tools.map { it.name },
            path = trail.path,
            command = command.differentFrom(said),
            fallbacks = trail.fallbacks,
            jev = selection.jev,
            asks = guarded.asks.map { it.why },
            refused = if (verdict.decision == Decision.Refuse) refusal(reply, selection.tools, enabled) else null,
        )
    }

    /** Why Needle 3's step gave nothing to run: tool names only, no words of the request. */
    private fun refusal(reply: NeedleReply, given: List<Tool>, enabled: List<Tool>): String = when {
        enabled.isEmpty() -> "no tool is on"
        reply.error != null -> "Needle 3: engine error"
        else -> "Needle 3: no call (given ${given.ifEmpty { enabled }.joinToString { it.name }.let { if (given.isEmpty()) "every tool: $it" else it }}; " +
            "held back ${reply.suppressed.joinToString { it.name }.ifEmpty { "nothing" }})"
    }

    /** What a request's steps record as they run: its path, and the fallbacks it took. */
    private class Trail(start: List<Step>) {
        val path = start.toMutableList()
        val fallbacks = mutableListOf<Fallback>()
    }

    /** The rewrite by the pipeline's step: one command, NONE (a [Rewritten] without one), or null for the words as they are. */
    private suspend fun rewrite(said: String, step: RewriteStep, geminiUsable: Boolean, status: LinkStatus, trail: Trail): Rewritten? {
        suspend fun viaGemini(instead: Boolean = false): Rewritten? {
            asking("Gemini Nano is rewriting it…")
            return try {
                gemini(said).also { trail.path += Step(if (instead) "Rewrite: Gemini Nano instead" else "Rewrite: Gemini Nano") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                trail.fallbacks += Fallback("Rewrite", "your words as they are", "Gemini Nano: ${e.message ?: e}")
                trail.path += Step("Rewrite: off (Gemini Nano failed)")
                null
            }
        }
        suspend fun linkFailed(why: String): Rewritten? =
            if (geminiUsable) {
                trail.fallbacks += Fallback("Rewrite", "Gemini Nano", "Ferb Link: $why")
                viaGemini(instead = true)
            } else {
                trail.fallbacks += Fallback("Rewrite", "your words as they are", "Ferb Link: $why")
                trail.path += Step("Rewrite: off (Ferb Link: $why)")
                null
            }
        return when (step) {
            RewriteStep.Off -> null
            RewriteStep.Gemini -> viaGemini()
            RewriteStep.Link -> when (val ready = status) {
                is LinkStatus.Ready -> {
                    asking(if (ready.asleep) "Waking your computer…" else "Your computer is rewriting it…")
                    // A sleeping model loads first (about 30 s cold): the rewrite waits for it, as a transcription does.
                    val timeoutMs = FerbLink.timeout(FerbLink.REWRITE_TIMEOUT_MS, ready.asleep)
                    // A, one line with the content quoted, for a Ferb Link that can't use a schema: an
                    // older one, one that ignored it, or a server that can't make it a grammar.
                    suspend fun line(): Rewritten? {
                        val reply = link.rewrite(said, ContentRewrite.PROMPT_A, ContentRewrite.MAX_TOKENS_A, timeoutMs = timeoutMs)
                        val raw = reply.raw
                        val error = reply.error
                        return when {
                            error != null -> linkFailed(error)
                            // An older Ferb Link answered with its own prompt: not this step's rewrite.
                            !reply.instructed || raw == null -> linkFailed("too old, update it")
                            else -> runCatching { ContentRewrite.parseLine(said, raw) }.fold(
                                { trail.path += Step("Rewrite: Ferb Link", listOfNotNull("one line", reply.ms?.let { ms -> "$ms ms" }).joinToString(", ")); it },
                                { linkFailed("unreadable answer") },
                            )
                        }
                    }
                    if (!ready.jsonSchema) line() else {
                        val reply = link.rewrite(said, ContentRewrite.PROMPT, ContentRewrite.MAX_TOKENS, timeoutMs = timeoutMs, jsonSchema = ContentRewrite.SCHEMA)
                        val output = reply.output
                        val error = reply.error
                        when {
                            reply.schemaUnsupported -> line()
                            error != null -> linkFailed(error)
                            !reply.instructed -> linkFailed("too old, update it")
                            // A reply without schema true didn't use it: unsupported, so the line prompt.
                            !reply.schema -> line()
                            output == null -> linkFailed("unreadable answer")
                            else -> runCatching { ContentRewrite.parse(said, output) }.fold(
                                { trail.path += Step("Rewrite: Ferb Link", reply.ms?.let { ms -> "$ms ms" }); it },
                                { linkFailed("unreadable answer") },
                            )
                        }
                    }
                }
                else -> linkFailed(brief(ready))
            }
        }
    }

    /** The tools Needle 3 weighs: the routed ones, or Jev's pick; or no tool, when Jev says none fits. */
    private sealed interface Selection {
        val jev: JevReply?
        data class Tools(val tools: List<Tool>, override val jev: JevReply?) : Selection
        data class NoTool(override val jev: JevReply) : Selection
    }

    private suspend fun select(text: String, routed: List<Tool>, enabled: List<Tool>, step: ToolStep, status: LinkStatus, trail: Trail): Selection {
        if (step != ToolStep.Link || enabled.isEmpty()) return Selection.Tools(routed, null)
        val ready = status
        when {
            ready !is LinkStatus.Ready -> {
                trail.fallbacks += Fallback("Tool selection", "Needle 3 alone", "Ferb Link: ${brief(ready)}")
                trail.path += Step("Tool selection: off (Ferb Link: ${brief(ready)})")
            }
            !ready.jev -> {
                val why = if (ready.jevCapped) "Jev reached its spending limit: raise it in Ferb Link" else "Jev is off in Ferb Link"
                trail.fallbacks += Fallback("Tool selection", "Needle 3 alone", why)
                trail.path += Step("Tool selection: off (${if (ready.jevCapped) "Jev's limit reached" else "Jev off"})")
            }
            else -> {
                asking("Asking Jev…")
                val reply = link.decide(text, Tools.json(routed.ifEmpty { enabled }))
                return when (val pick = LinkPolicy.select(reply, enabled.map { it.name })) {
                    is JevPick.Tool -> {
                        trail.path += Step("Tool selection: Jev", pick(reply))
                        Selection.Tools(listOfNotNull(Tools.byName(pick.name)), reply)
                    }
                    JevPick.NoTool -> {
                        trail.path += Step("Tool selection: Jev", pick(reply))
                        Selection.NoTool(reply)
                    }
                    is JevPick.Fallback -> {
                        trail.fallbacks += Fallback("Tool selection", "Needle 3 alone", pick.detail)
                        trail.path += Step("Tool selection: off (${pick.reason})")
                        Selection.Tools(routed, reply)
                    }
                }
            }
        }
        return Selection.Tools(routed, null)
    }

    /**
     * Needle 3's reply with the guards applied: [shown] (the content as said, Ferb's own times),
     * [gated] (each call's date gate, null for an untimed one), [asks] (the content guards),
     * [otherTool] (gate H: the words as said name other timed tools, not this one).
     */
    private class Guarded(val shown: NeedleReply, val gated: List<DateGate.Gated?>, val asks: List<Guard>, val otherTool: Boolean)

    private fun guard(reply: NeedleReply, said: String, command: String?, routed: List<Tool>, saidRouted: List<Tool>, at: ZonedDateTime, trail: Trail): Guarded {
        fun timed(tools: List<Tool>) = tools.map { it.name }.filter(DateGate::timed)
        // Timed calls (2026-10-25, the blind date round): the engine held one back but the router
        // names that timed tool alone, so it comes back to be weighed (gate E).
        val heldBack = reply.suppressed.firstOrNull { DateGate.timed(it.name) && timed(routed) == listOf(it.name) }
        val needled = if (reply.calls.isEmpty() && heldBack != null) listOf(heldBack) else reply.calls
        // Each call with its content as said (a rewritten request only): times are read outside it ([DateGate.outside]).
        val mapped = needled.map { call -> mapContent(call, said, command) }
        val calls = mapped.map { it.call }
        val kept = mapped.map { it.kept }
        val asks = buildList {
            if (mapped.any { it.notSaid }) add(Guard.NotSaid)
            if (command != null && calls.isNotEmpty()) {
                val named = saidRouted.map { it.name }
                if (named.isNotEmpty() && calls.first().name !in named) add(Guard.OtherTool)
                val (fromSaid, fromCommand) = DateGate.readings(TIME_GUARD_TOOL, said, command, at, kept.firstOrNull { it != null })
                if (fromSaid !is DateGate.Read.Ask && fromCommand is DateGate.Read.Ask) add(Guard.TimeDropped)
            }
        }
        // Ferb's own time: the words as said and the rewrite read alike ([DateGate]); Needle 3's never decides.
        val gated = calls.mapIndexed { i, call -> if (DateGate.timed(call.name)) gate(call.name, call.arguments, said, command, at, kept[i]) else null }
        val shown = reply.copy(
            calls = calls.mapIndexed { i, call -> gated[i]?.let { g -> call.copy(arguments = g.arguments, time = g.time) } ?: call },
            suppressed = reply.suppressed.filter { it !== heldBack },
        )
        gated.filterNotNull().firstOrNull()?.let { g ->
            trail.path += Step("Dates: Ferb", if (g.agreed) DateGate.shown(g.said) else "asks: ${g.time?.ask}")
        }
        // The words as said name other timed tools, not this one ("remind me" rewritten as an alarm): ask (gate H).
        val saidTimed = timed(saidRouted)
        val otherTool = calls.any { DateGate.timed(it.name) && saidTimed.isNotEmpty() && it.name !in saidTimed }
        asks.forEach { trail.path += Step("Asks first", it.why) }
        return Guarded(shown, gated, asks, otherTool)
    }

    /** A call with its content argument as said ([kept], where it mapped), and whether it couldn't be found there ([notSaid]). */
    private class Mapped(val call: ToolCall, val kept: String?, val notSaid: Boolean)

    /** [call]'s content mapped back to the words as said ([ContentMap]); a call with none, or an unrewritten request, as it is. */
    private fun mapContent(call: ToolCall, said: String, command: String?): Mapped {
        val field = ContentMap.CONTENT[call.name]
        if (command == null || field == null) return Mapped(call, null, false)
        val c = content.contentOf(call.arguments[field]?.toString(), ContentMap.quoted(command),
            call.arguments.filterKeys { it != field && !it.startsWith("_") }, said)
        return Mapped(call.copy(arguments = call.arguments + (field to c.text)), c.text.takeIf { c.mapped }, !c.mapped)
    }

    /** The verdict on the guarded calls: the tools' bars, then the guards' asks and the agreed dates. */
    private fun decide(guarded: Guarded, configs: Map<String, ToolConfig>, text: String, said: String, command: String?): Verdict {
        val shown = guarded.shown
        // The content is the words as said, so they ground it too; the command grounds the rest.
        val v = Verdict.of(shown, configs, if (command == null) text else "$text\n$said")
        return when {
            v.decision == Decision.Refuse || shown.calls.isEmpty() -> v
            guarded.asks.isNotEmpty() -> v.copy(decision = Decision.Confirm, ungrounded = true)
            guarded.otherTool -> v.copy(decision = Decision.Confirm, ungrounded = true)
            // A number the rewrite brought in that the request never said ("wake me up early" as
            // "6 am"): its calls ask first, as a value the request doesn't say does.
            command != null && SaidNumbers.invents(command, said) -> v.copy(decision = Decision.Confirm, ungrounded = true)
            // Timed calls whose two readings agree run, whatever Needle 3's confidence (gate E),
            // unless the tool always asks or the request doesn't mention it.
            guarded.gated.all { it?.agreed == true } && !v.alwaysConfirm && !v.unmentioned && !v.ungrounded -> v.copy(decision = Decision.Run)
            else -> v
        }
    }

    private fun String?.differentFrom(said: String) = this?.takeUnless { it.equals(said.trim(), ignoreCase = true) }

    private suspend fun askNeedle(request: String, enabled: List<Tool>, routed: List<Tool>): NeedleReply = when {
        routed.isNotEmpty() -> needle(request, routed)
        enabled.isEmpty() -> NO_TOOL
        else -> needle(request, enabled)
    }

    companion object {
        /** The tool the time guard reads the two texts as (the frozen code's: a reminder's time and date). */
        private const val TIME_GUARD_TOOL = "add_reminder"

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

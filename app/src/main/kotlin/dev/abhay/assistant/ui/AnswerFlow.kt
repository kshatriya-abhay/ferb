package dev.abhay.assistant.ui

import dev.abhay.assistant.engine.Decision
import dev.abhay.assistant.engine.NeedleReply
import dev.abhay.assistant.engine.ToolCall
import dev.abhay.assistant.link.Answerer
import dev.abhay.assistant.link.JevPick
import dev.abhay.assistant.link.JevReply
import dev.abhay.assistant.link.LinkCalls
import dev.abhay.assistant.link.LinkMode
import dev.abhay.assistant.link.LinkPolicy
import dev.abhay.assistant.link.LinkStatus
import dev.abhay.assistant.link.brief
import dev.abhay.assistant.link.describe
import dev.abhay.assistant.tools.Tool
import dev.abhay.assistant.tools.ToolConfig
import dev.abhay.assistant.tools.AddEvent
import dev.abhay.assistant.tools.AddReminder
import dev.abhay.assistant.tools.SetAlarm
import dev.abhay.assistant.tools.SetTimer
import dev.abhay.assistant.tools.TimeArgs
import dev.abhay.assistant.tools.ToolRouter
import dev.abhay.assistant.tools.Tools
import dev.abhay.assistant.tools.Verdict
import java.time.ZonedDateTime
import java.util.Locale

/**
 * One step a request took, recorded as it ran, for the details' chain ("Typed → Jev (Alarm, 0.93)
 * → Needle 3 (0.81)"): [name], and [detail] such as the pick and its probability. A step that
 * failed says so in its name ("Jev timed out"), and the next step is what answered instead.
 */
data class Step(val name: String, val detail: String? = null) {
    val text get() = if (detail == null) name else "$name ($detail)"

    companion object {
        fun chain(steps: List<Step>) = steps.joinToString(" → ") { it.text }
    }
}

/** One command of a rewritten request ("Understood as"), the steps it took, and its calls. */
data class CommandPath(val text: String, val steps: List<Step>, val calls: List<ToolCall>)

/**
 * Ferb's answer to a request before anything runs: [shown], the reply whose calls are offered,
 * each call with the text its values are read from ([callTexts]: the request, or the command it
 * came from); the decision; and how it got there ([path], [commands]).
 */
data class Answer(
    val shown: NeedleReply,
    val callTexts: List<String>,
    val verdict: Verdict,
    /** The tools the answering model was given (empty: every tool turned on). */
    val checked: List<String>,
    val path: List<Step>,
    /** For a rewritten request: its commands, in order, each with its steps. */
    val commands: List<CommandPath> = emptyList(),
    val laptopError: String? = null,
    val laptopErrorDetail: String? = null,
    val jev: JevReply? = null,
    val jevNote: String? = null,
    val jevNoteDetail: String? = null,
)

/**
 * Who answers a request, in which order, and with which tools, by mode (Settings → Ferb Link),
 * the chat box's choice and "Use Jev to select the tool"; [link] and [needle] do the asking.
 * Every step is recorded as it runs ([Step]). With Jev on, in Computer mode, a spoken request
 * takes the commands path: the transcription's English text (already a rewrite by the computer's
 * model) to Jev, then to Needle 3 with Jev's tool. On the audio bench's real speech (2026-10-16)
 * that got 58 of 67 right with 36 run, against Gemma with Jev's tools' 57 and 23. A failed Jev
 * falls back to Gemma, saying why. Typed requests in Computer mode stay with Gemma and Jev's
 * tools until a "comprehend" step (Gemma deciding whether to rewrite) has been benched.
 */
class AnswerFlow(
    private val link: LinkCalls,
    private val needle: suspend (request: String, tools: List<Tool>) -> NeedleReply,
    private val asking: (String?) -> Unit = {},
    private val now: () -> ZonedDateTime = ZonedDateTime::now,
) {
    /**
     * [reply] with each call's time structure, if any, resolved by Ferb into its arguments
     * ([TimeArgs]), checked against [texts] (the call's text, and the heard words of a spoken one).
     */
    private fun timed(reply: NeedleReply, texts: (Int) -> List<String>): NeedleReply {
        val at = now()
        return reply.copy(calls = reply.calls.mapIndexed { i, call -> call.copy(arguments = TimeArgs.apply(call.name, call.arguments, texts(i), at)) })
    }

    suspend fun answer(
        request: String,
        heard: String?,
        enabled: List<Tool>,
        configs: Map<String, ToolConfig>,
        mode: LinkMode,
        answerer: Answerer,
        jevSwitch: Boolean,
        /**
         * A request heard on the phone (Phone-mode voice), with the steps that wrote it down:
         * ["Whistle"], or ["Gemini Nano"] and, for Hindi, ["Gemini Nano", "Gemini Nano (English)"].
         */
        phoneVoice: List<String>? = null,
    ): Answer {
        val routed = ToolRouter.route(request, enabled, configs)
        val chosen = LinkPolicy.laptopFirst(mode, answerer)
        val path = mutableListOf<Step>()
        path += when {
            phoneVoice != null -> listOf(Step("Voice")) + phoneVoice.map { Step(it) }
            heard != null -> listOf(Step("Voice"), Step("Gemma transcription"))
            else -> listOf(Step("Typed"))
        }
        val status = if (mode != LinkMode.Off && enabled.isNotEmpty()) link.status() else null
        val jevOn = jevSwitch && status is LinkStatus.Ready && status.jev
        var jevNote: String? = null
        var jevNoteDetail: String? = null
        if (jevSwitch && status is LinkStatus.Ready && !status.jev && status.jevCapped) {
            jevNote = "Jev's limit reached"
            jevNoteDetail = "Jev reached its spending limit: raise it in Ferb Link"
        }

        // Computer mode with Jev on: speech takes the commands path.
        if (chosen && jevOn && heard != null) {
            when (val got = commands(listOf(request), heard, enabled, configs, path)) {
                is Commands.Answered -> return got.answer
                is Commands.Failed -> {
                    jevNote = got.reason
                    jevNoteDetail = got.detail
                }
            }
        }

        var jev: JevReply? = null
        var jevNone = false
        var jevPicked = false
        var needleTools = routed
        var laptopTools = routed.ifEmpty { enabled }
        // "Use Jev to select the tool" for the request itself, unless the commands path already fell back from it.
        if (jevOn && jevNote == null) {
            asking("Asking Jev…")
            val reply = link.decide(request, Tools.json(routed.ifEmpty { enabled }))
            jev = reply
            when (val pick = LinkPolicy.select(reply, enabled.map { it.name })) {
                is JevPick.Tool -> {
                    needleTools = listOfNotNull(Tools.byName(pick.name))
                    laptopTools = (listOf(pick.name) + pick.also).mapNotNull(Tools::byName)
                    jevPicked = true
                    path += Step("Jev", pick(reply))
                }
                JevPick.NoTool -> {
                    jevNone = true
                    path += Step("Jev", pick(reply))
                }
                is JevPick.Fallback -> {
                    jevNote = pick.reason
                    jevNoteDetail = pick.detail
                    path += Step(pick.reason)
                }
            }
        }

        var laptop: NeedleReply? = null
        var laptopError: String? = null
        var laptopErrorDetail: String? = null
        suspend fun askLaptop() {
            val now = link.status()
            if (now is LinkStatus.Ready) {
                // The model not loaded: it loads first, so a longer wait, said so.
                asking(if (now.asleep) "Waking your computer…" else "Asking your computer…")
                // Jev picked a timed tool (Computer mode): the timed call, with that tool alone, for
                // the time as structure; if it fails, the usual call, saying why (the user's
                // decision, 2026-10-19: wrong times 11 → 5 of 75 held out, about 1 s more).
                val timedTool = laptopTools.firstOrNull()?.takeIf { chosen && jevPicked && it.name in TIMED }
                if (timedTool != null) {
                    val reply = link.complete(request, Tools.json(listOf(timedTool)), waking = now.asleep, timeFormat = true)
                    if (reply.error == null) {
                        laptop = reply
                        laptopError = null
                        path += Step("Gemma (time)")
                        return
                    }
                    path += Step("Gemma (time): ${reply.error}")
                    jevNote = "Time: ${reply.error}"
                    jevNoteDetail = reply.errorDetail ?: reply.error
                }
                val reply = link.complete(request, Tools.json(laptopTools), waking = now.asleep)
                laptop = reply
                laptopError = reply.error
                laptopErrorDetail = reply.errorDetail ?: reply.error
                path += Step(if (reply.error == null) "Gemma" else "Gemma: ${reply.error}")
            } else {
                // Picked in the chat box, so say why it didn't answer.
                laptopError = brief(now)
                laptopErrorDetail = describe(now)
                path += Step("Computer: ${brief(now)}")
            }
        }
        var needleReply: NeedleReply? = null
        if (jevNone) {
            // Jev says no tool fits: neither model is asked.
            needleReply = NO_TOOL
        } else {
            if (chosen && enabled.isNotEmpty()) askLaptop()
            if (laptop?.error != null || laptop == null) {
                asking(null)
                val reply = askNeedle(request, enabled, needleTools)
                needleReply = reply
                if (enabled.isNotEmpty()) path += needleStep(reply)
            }
        }
        // The computer's answer whenever it gave one, including "no tool fits".
        val shown = timed(laptop?.takeIf { it.error == null } ?: needleReply!!) { listOfNotNull(request, heard) }
        val verdict = if (shown.fromLaptop) LinkPolicy.verdict(shown, configs, request) else Verdict.of(shown, configs, request)
        return Answer(
            shown = shown,
            callTexts = shown.calls.map { request },
            verdict = verdict,
            checked = (if (jevPicked && shown.fromLaptop) laptopTools else needleTools).map { it.name },
            path = path,
            laptopError = laptopError.takeIf { !shown.fromLaptop },
            laptopErrorDetail = laptopErrorDetail.takeIf { !shown.fromLaptop },
            jev = jev,
            jevNote = jevNote,
            jevNoteDetail = jevNoteDetail,
        )
    }

    private sealed interface Commands {
        data class Answered(val answer: Answer) : Commands
        data class Failed(val reason: String, val detail: String) : Commands
    }

    /**
     * The commands path: English commands (a spoken request's transcription), each to Jev and to
     * Needle 3 with Jev's tool, checked against the command. [invented]: a command holds a fact
     * the request didn't (never runs without asking). Failed when Jev fails: the request then goes
     * on as without it (to the computer), and [path] says where it stopped.
     */
    private suspend fun commands(texts: List<String>, heard: String?, enabled: List<Tool>, configs: Map<String, ToolConfig>, path: MutableList<Step>, invented: Boolean = false): Commands {
        val names = enabled.map { it.name }
        val done = mutableListOf<Triple<String, NeedleReply?, List<Step>>>()
        for (text in texts) {
            asking("Asking Jev…")
            val tools = ToolRouter.route(text, enabled, configs).ifEmpty { enabled }
            val jev = link.decide(text, Tools.json(tools))
            when (val pick = LinkPolicy.select(jev, names)) {
                is JevPick.Fallback -> {
                    path += Step(pick.reason)
                    return Commands.Failed(pick.reason, pick.detail)
                }
                JevPick.NoTool -> done += Triple(text, null, listOf(Step("Jev", pick(jev))))
                is JevPick.Tool -> {
                    val tool = listOfNotNull(Tools.byName(pick.name))
                    // A timed pick: the computer's timed call on the transcription's text, for the
                    // time as structure, as typed requests in Computer mode (2026-10-19); Needle 3
                    // if it fails.
                    val viaComputer = if (heard != null && pick.name in TIMED) {
                        asking("Asking your computer\u2026")
                        link.complete(text, Tools.json(tool), timeFormat = true)
                    } else null
                    if (viaComputer != null && viaComputer.error == null) {
                        val reply = timed(viaComputer) { listOfNotNull(text, heard) }
                        done += Triple(text, reply, listOf(Step("Jev", pick(jev)), Step("Gemma (time)")))
                    } else {
                        asking(null)
                        val reply = timed(needle(text, tool)) { listOfNotNull(text, heard) }
                        done += Triple(text, reply, listOfNotNull(Step("Jev", pick(jev)), viaComputer?.let { Step("Gemma (time): ${it.error}") }, needleStep(reply)))
                    }
                }
            }
        }
        // One command: its steps continue the chain; several: each has its own, under "Understood as".
        if (done.size == 1) path += done.single().third
        val calls = done.flatMap { (text, reply, _) -> reply?.calls.orEmpty().map { it to text } }
        val verdicts = done.mapNotNull { (text, reply, _) ->
            reply?.let { if (it.fromLaptop) LinkPolicy.verdict(it, configs, text) else Verdict.of(it, configs, text) }
        }
        val decision = when {
            calls.isEmpty() -> Decision.Refuse
            // A fact the request didn't hold (Ferb Link's `invented`): never without asking.
            invented || verdicts.any { it.decision != Decision.Run } -> Decision.Confirm
            else -> Decision.Run
        }
        val first = done.firstNotNullOfOrNull { it.second } ?: NO_TOOL
        val shown = first.copy(calls = calls.map { it.first }, suppressed = done.flatMap { it.second?.suppressed.orEmpty() })
        val verdict = Verdict(
            decision = decision,
            runAt = verdicts.maxOfOrNull { it.runAt } ?: Decision.RUN_AT,
            alwaysConfirm = verdicts.any { it.alwaysConfirm },
            unmentioned = verdicts.any { it.unmentioned },
            ungrounded = verdicts.any { it.ungrounded } || invented,
        )
        return Commands.Answered(Answer(
            shown = shown,
            callTexts = calls.map { it.second },
            verdict = verdict,
            checked = calls.map { it.first.name }.distinct(),
            path = path,
            commands = done.map { (text, reply, steps) -> CommandPath(text, steps, reply?.calls.orEmpty()) },
        ))
    }

    private suspend fun askNeedle(request: String, enabled: List<Tool>, routed: List<Tool>): NeedleReply = when {
        routed.isNotEmpty() -> needle(request, routed)
        enabled.isEmpty() -> NO_TOOL
        else -> needle(request, enabled)
    }

    companion object {
        /** The tools whose time the timed call gives as structure ([TimeArgs]). */
        val TIMED = setOf(SetAlarm.name, SetTimer.name, AddReminder.name, AddEvent.name)

        /** The answer when no tool is on, or Jev says none fits. */
        val NO_TOOL = NeedleReply(
            calls = emptyList(), suppressed = emptyList(), reasoning = "", confidence = null, ungrounded = emptyList(),
            negation = false, prefillTps = 0.0, decodeTps = 0.0, peakRamMb = 0.0, error = null,
        )

        /** Jev's pick for the chain: "Alarm, 0.93", or "no tool, 0.80". */
        fun pick(jev: JevReply) = String.format(Locale.US, "%s, %.2f", jev.choice?.let { Tools.byName(it)?.title } ?: "no tool", jev.p ?: 0.0)

        /** Needle 3's step: its confidence, or why it failed. */
        fun needleStep(reply: NeedleReply) = when {
            reply.error != null -> Step("Needle 3: ${reply.error}")
            else -> Step("Needle 3", reply.confidence?.let { String.format(Locale.US, "%.2f", it) })
        }
    }
}

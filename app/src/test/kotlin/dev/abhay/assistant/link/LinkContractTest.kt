package dev.abhay.assistant.link

import com.google.common.truth.Truth.assertThat
import dev.abhay.assistant.ui.VoiceOutcome
import java.io.File
import org.json.JSONObject
import org.junit.Test

/**
 * Ferb's side of the link to Ferb Link, against Ferb Link's own examples: each fixture is what Ferb
 * sends (`extras`) and the Bundle Ferb Link returns (`result`), from ferb-link's contract/v1, copied
 * here by `mise run link:contract-sync`. Ferb parses each result with the app's own code, so a
 * change on either side that breaks the other fails here, with neither running.
 */
class LinkContractTest {
    private class Case(val name: String, val method: String, val extras: JSONObject, val result: JSONObject, val synthetic: Boolean)

    private val cases: List<Case> = run {
        val dir = File(javaClass.classLoader!!.getResource("ferb-link-contract/v1")!!.toURI())
        dir.listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }.map { file ->
            val o = JSONObject(file.readText())
            Case(file.nameWithoutExtension, o.getString("method"), o.optJSONObject("extras") ?: JSONObject(),
                o.getJSONObject("result"), o.optBoolean("synthetic"))
        }
    }

    /** A fixture's result as Ferb reads a Bundle; `reply`, an object in the fixture, travels as a string. */
    private fun values(result: JSONObject) = LinkResult { key ->
        when (val v = result.opt(key)) {
            null, JSONObject.NULL -> null
            is JSONObject -> v.toString()
            else -> v
        }
    }

    private fun of(method: String) = cases.filter { it.method == method && !it.result.has("throws") }

    /** A short error, as the chat shows it after "On phone ·": a few words, lower case, no full stop. */
    private fun assertShort(error: String?) {
        assertThat(error).isNotNull()
        assertThat(error!!.length).isAtMost(40)
        assertThat(error.first().isLowerCase()).isTrue()
        assertThat(error).doesNotContain(".")
    }

    @Test
    fun theFixturesAreHereAndKnown() {
        assertThat(cases).isNotEmpty()
        val methods = listOf("status", "check", "complete", "transcribe", "wake", "decide", "rewrite")
        cases.filter { !it.result.has("throws") }.forEach { assertThat(it.method).isIn(methods) }
        methods.forEach { assertThat(of(it)).isNotEmpty() }
    }

    @Test
    fun ferbSendsWhatFerbLinkTakes() {
        // Every key Ferb puts in a call's extras is one Ferb Link's examples take (some take more,
        // optional ones Ferb doesn't send, such as facts).
        val sent = mapOf(
            "complete" to setOf(LinkContract.EXTRA_REQUEST, LinkContract.EXTRA_TOOLS, LinkContract.EXTRA_TIMEOUT_MS, LinkContract.EXTRA_TIME_FORMAT),
            "transcribe" to setOf(LinkContract.EXTRA_AUDIO, LinkContract.EXTRA_AUDIO_MIME, LinkContract.EXTRA_TIMEOUT_MS),
            "decide" to setOf(LinkContract.EXTRA_REQUEST, LinkContract.EXTRA_TOOLS, LinkContract.EXTRA_TIMEOUT_MS),
        )
        sent.forEach { (method, keys) ->
            val taken = of(method).flatMap { it.extras.keys().asSequence().toList() }.toSet()
            assertThat(taken).containsAtLeastElementsIn(keys)
        }
    }

    @Test
    fun statusIsReadyOffNotSetUpOrAnotherVersion() {
        of("status").forEach { case ->
            val r = case.result
            val status = FerbLink.statusOf(values(r))
            when {
                r.optInt("version", -1) != LinkContract.VERSION -> assertThat(status).isInstanceOf(LinkStatus.Mismatch::class.java)
                !r.optBoolean("enabled") -> assertThat(status).isEqualTo(LinkStatus.Off)
                !r.optBoolean("configured") -> assertThat(status).isEqualTo(LinkStatus.NotSetUp)
                else -> assertThat(status).isEqualTo(LinkStatus.Ready(r.optString("model").ifBlank { null }, r.optBoolean("audio"),
                    asleep = FerbLink.asleep(r.optString("model_state").ifEmpty { null }),
                    jev = r.optBoolean("jev"), jevCapped = r.optString("jev_off_reason") == "cap"))
            }
        }
        // model_state: asleep (or unloaded) means the next request waits longer; loaded or missing doesn't.
        of("status").filter { it.result.optInt("version", -1) == LinkContract.VERSION && it.result.optBoolean("enabled") && it.result.optBoolean("configured") }
            .forEach { case ->
                val asleep = (FerbLink.statusOf(values(case.result)) as LinkStatus.Ready).asleep
                assertThat(asleep).isEqualTo(FerbLink.asleep(case.result.optString("model_state").ifEmpty { null }))
            }
        assertThat(of("status").map { FerbLink.statusOf(values(it.result)) }.filterIsInstance<LinkStatus.Ready>().any { it.asleep }).isTrue()
        // A ready Ferb Link names its model, and whether it takes audio (the mic needs it).
        val ready = of("status").map { FerbLink.statusOf(values(it.result)) }.filterIsInstance<LinkStatus.Ready>()
        assertThat(ready).isNotEmpty()
        ready.forEach { assertThat(it.model).isNotNull() }
    }

    @Test
    fun statusSaysWhetherJevIsOnAndWhenItsLimitTurnedItOff() {
        val ready = of("status").map { FerbLink.statusOf(values(it.result)) }.filterIsInstance<LinkStatus.Ready>()
        assertThat(ready.any { it.jev }).isTrue()
        assertThat(ready.any { it.jevCapped && !it.jev }).isTrue()
    }

    @Test
    fun decideIsAPickWithItsProbabilityOrAShortError() {
        of("decide").forEach { case ->
            val jev = FerbLink.decisionOf(values(case.result))
            val wire = case.result.getJSONObject("reply")
            if (wire.optBoolean("success")) {
                // A pick among the options it was asked, "none" included, with its probability.
                val options = wire.getJSONArray("options").let { a -> (0 until a.length()).map(a::getString) }
                assertThat(jev.choice).isIn(options)
                assertThat(jev.p).isWithin(1e-9).of(wire.getJSONObject("probabilities").getDouble(jev.choice!!))
                assertThat(jev.error).isNull()
            } else {
                // Never read as a pick: Ferb behaves as without Jev, and the details say why.
                assertThat(jev.p).isNull()
                // A few words, lower case but for Jev's name ("Jev's limit reached").
                val error = jev.error!!
                assertThat(error.length).isAtMost(40)
                assertThat(error).doesNotContain(".")
                assertThat(error.first().isLowerCase() || error.startsWith("Jev")).isTrue()
                assertThat(LinkPolicy.jevLine(jev)).isNotEmpty()
            }
        }
        assertThat(of("decide").map { FerbLink.decisionOf(values(it.result)).choice }).contains(JevReply.NONE)
        // At the spending limit, the details say so.
        val capped = of("decide").map { FerbLink.decisionOf(values(it.result)) }.filter { it.errorDetail?.contains("spending limit") == true }
        assertThat(capped).isNotEmpty()
        capped.forEach { assertThat(LinkPolicy.jevLine(it)).contains("spending limit") }
    }

    @Test
    fun rewriteIsCommandsOrAShortError() {
        of("rewrite").forEach { case ->
            val rewrite = FerbLink.rewriteOf(values(case.result))
            val wire = case.result.getJSONObject("reply")
            if (wire.optBoolean("success")) {
                assertThat(rewrite.error).isNull()
                assertThat(rewrite.commands).hasSize(wire.getJSONArray("commands").length())
            } else {
                assertThat(rewrite.commands).isEmpty()
                assertShort(rewrite.error)
            }
        }
        val all = of("rewrite").map { FerbLink.rewriteOf(values(it.result)) }
        // One command, two, none, and one with a fact the request didn't hold.
        assertThat(all.map { it.commands.size }).containsAtLeast(0, 1, 2)
        assertThat(all.any { it.invented.isNotEmpty() }).isTrue()
    }

    @Test
    fun aTimedCompleteCarriesEachCallsTimeAndAsks() {
        val timed = of("complete").filter { it.extras.optInt("time_format") == 1 }
        assertThat(timed).isNotEmpty()
        val now = java.time.LocalDateTime.parse("2026-10-19T08:25").atZone(java.time.ZoneId.of("Asia/Kolkata"))
        timed.forEach { case ->
            val reply = FerbLink.replyOf(values(case.result))
            val wire = case.result.getJSONObject("reply")
            if (!wire.optBoolean("success", true)) { assertShort(reply.error); return@forEach }
            val calls = wire.optJSONArray("function_calls") ?: org.json.JSONArray()
            assertThat(reply.calls).hasSize(calls.length())
            reply.calls.forEachIndexed { i, call ->
                val o = calls.getJSONObject(i)
                // Ferb Link's asks, or a trimmed value, make the call ask; a "when" reaches TimeArgs.
                val asks = o.optJSONArray("asks")?.length() ?: 0
                if (asks > 0 || o.optBoolean("trimmed")) assertThat(call.arguments[dev.abhay.assistant.tools.TimeArgs.ASK]).isNotNull()
                else assertThat(call.arguments[dev.abhay.assistant.tools.TimeArgs.ASK]).isNull()
                if (!o.isNull("when")) {
                    assertThat(dev.abhay.assistant.tools.TimeExpr.parse(call.arguments["when"])).isNotNull()
                    // And Ferb resolves it, against the request as typed.
                    dev.abhay.assistant.tools.TimeArgs.apply(call.name, call.arguments, listOf(case.extras.getString("request")), now)
                }
            }
        }
        // The fixtures cover an alarm, "in", a repeat, an ask, a trimmed value and no call.
        val kinds = timed.flatMap { c -> c.result.getJSONObject("reply").optJSONArray("function_calls")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty() }
        assertThat(kinds.mapNotNull { it.optJSONObject("when")?.optString("kind") }.toSet()).containsAtLeast("at", "in", "every")
        assertThat(kinds.any { it.optBoolean("trimmed") }).isTrue()
    }

    @Test
    fun checkSaysWhetherTheComputerAnswersAndWhyNot() {
        of("check").forEach { case ->
            val found = FerbLink.reachabilityOf(values(case.result))
            if (found.reachable) assertThat(found.model).isNotNull()
            else {
                assertShort(found.error)
                assertThat(found.errorDetail).isNotNull()
            }
        }
    }

    @Test
    fun completeRepliesAreCallsOrShortErrors() {
        of("complete").forEach { case ->
            val reply = FerbLink.replyOf(values(case.result))
            assertThat(reply.fromLaptop).isTrue()
            val wire = case.result.optJSONObject("reply")
            if (case.result.optInt("version", -1) != LinkContract.VERSION) {
                assertThat(reply.error).isNotNull()
            } else if (wire == null || wire.optBoolean("success", true)) {
                assertThat(reply.error).isNull()
                val calls = wire?.optJSONArray("function_calls")
                assertThat(reply.calls.map { it.name }).isEqualTo((0 until (calls?.length() ?: 0)).map { calls!!.getJSONObject(it).getString("name") })
                assertThat(reply.model).isNotNull()
            } else if (wire.has("error_detail")) {
                // A few words for the chat, the reason at length for Details.
                assertShort(reply.error)
                assertThat(reply.errorDetail).isNotEmpty()
            } else {
                // An older Ferb Link: only a sentence, which the chat and Details both show.
                assertThat(reply.error).isNotEmpty()
                assertThat(reply.errorDetail).isNull()
            }
        }
    }

    @Test
    fun transcriptionsLeadToAnAnswerOrANotice() {
        of("transcribe").forEach { case ->
            val transcript = FerbLink.transcriptOf(values(case.result))
            val wire = case.result.optJSONObject("reply")
            val outcome = VoiceOutcome.of(transcript)
            when {
                transcript.error != null -> assertThat((outcome as VoiceOutcome.Failed).keepClip).isTrue()
                wire != null && wire.optString("text").isBlank() -> assertThat((outcome as VoiceOutcome.Failed).keepClip).isFalse()
                else -> assertThat((outcome as VoiceOutcome.Answer).request).isEqualTo(wire!!.getString("text").trim())
            }
        }
    }

    @Test
    fun anOlderFerbLinkThrowsForMethodsItDoesntKnow() {
        // FerbLink.check and transcribe take IllegalArgumentException as "too old" (check returns null,
        // transcribe asks to update Ferb Link).
        val unknown = cases.filter { it.result.has("throws") }
        assertThat(unknown).isNotEmpty()
        unknown.forEach { assertThat(it.result.getString("throws")).isEqualTo("IllegalArgumentException") }
    }
}

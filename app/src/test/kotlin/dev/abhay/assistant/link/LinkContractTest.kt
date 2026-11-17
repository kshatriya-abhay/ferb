package dev.abhay.assistant.link

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.abhay.assistant.ui.VoiceOutcome
import dev.abhay.assistant.voice.ContentRewrite
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
        // limits.json holds Ferb Link's numbers, not an exchange: [ferbsCopiesOfFerbLinksLimitsAgree].
        dir.listFiles { f -> f.extension == "json" && f.name != LIMITS }!!.sortedBy { it.name }.map { file ->
            val o = JSONObject(file.readText())
            Case(file.nameWithoutExtension, o.getString("method"), o.optJSONObject("extras") ?: JSONObject(),
                o.getJSONObject("result"), o.optBoolean("synthetic"))
        }
    }

    @Test
    fun ferbsCopiesOfFerbLinksLimitsAgree() {
        // Ferb keeps its own copies of these (it can't import Ferb Link's LinkContract); limits.json is Ferb Link's.
        val limits = JSONObject(File(javaClass.classLoader!!.getResource("ferb-link-contract/v1/$LIMITS")!!.toURI()).readText())
        assertThat(LinkContract.VERSION).isEqualTo(limits.getInt("VERSION"))
        assertThat(FerbLink.TRANSCRIBE_TIMEOUT_MS).isEqualTo(limits.getInt("TRANSCRIBE_TIMEOUT_MS"))
    }

    @Test
    fun whatFerbSendsFitsFerbLinksLimits() {
        // Review P2-117: past any of these, Ferb Link turns every rewrite or pick down as a bad request,
        // and Ferb quietly goes on without it.
        val limits = JSONObject(File(javaClass.classLoader!!.getResource("ferb-link-contract/v1/$LIMITS")!!.toURI()).readText())
        for ((name, prompt) in mapOf("PROMPT" to ContentRewrite.PROMPT, "PROMPT_A" to ContentRewrite.PROMPT_A))
            assertWithMessage(name).that(prompt.length).isAtMost(limits.getInt("MAX_INSTRUCTIONS_CHARS"))
        assertWithMessage("SCHEMA").that(ContentRewrite.SCHEMA.length).isAtMost(limits.getInt("MAX_JSON_SCHEMA_CHARS"))
        for ((name, tokens) in mapOf("MAX_TOKENS" to ContentRewrite.MAX_TOKENS, "MAX_TOKENS_A" to ContentRewrite.MAX_TOKENS_A))
            assertWithMessage(name).that(tokens).isAtMost(limits.getInt("MAX_REWRITE_TOKENS"))
        val timeouts = mapOf(
            "REWRITE_TIMEOUT_MS" to FerbLink.REWRITE_TIMEOUT_MS, "TRANSCRIBE_TIMEOUT_MS" to FerbLink.TRANSCRIBE_TIMEOUT_MS,
            "WAKING_TIMEOUT_MS" to FerbLink.WAKING_TIMEOUT_MS, "JEV_TIMEOUT_MS" to FerbLink.JEV_TIMEOUT_MS,
        )
        for ((name, ms) in timeouts)
            assertWithMessage(name).that(ms).isIn(com.google.common.collect.Range.closed(limits.getInt("MIN_TIMEOUT_MS"), limits.getInt("MAX_TIMEOUT_MS")))
        assertWithMessage("JEV_TIMEOUT_MS").that(FerbLink.JEV_TIMEOUT_MS).isAtMost(limits.getInt("MAX_DECIDE_TIMEOUT_MS"))
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
        // Ferb no longer asks for Gemma's tool calls ("complete", removed 2026-10-24); its fixtures stay Ferb Link's.
        val methods = listOf("status", "check", "transcribe", "wake", "decide", "rewrite")
        cases.filter { !it.result.has("throws") && it.method != "complete" }.forEach { assertThat(it.method).isIn(methods) }
        methods.forEach { assertThat(of(it)).isNotEmpty() }
    }

    @Test
    fun ferbSendsWhatFerbLinkTakes() {
        // The extras Ferb's calls really carry (FerbLink's builders), against Ferb Link's examples:
        // every key Ferb sends is one an example takes, as the same kind of value, and every key
        // all of a method's examples send, Ferb sends too.
        val sent = mapOf(
            "decide" to FerbLink.decideExtras("wake me up at 7", "[]", FerbLink.JEV_TIMEOUT_MS),
            "rewrite" to FerbLink.rewriteExtras("wake me up at 7", ContentRewrite.PROMPT, ContentRewrite.MAX_TOKENS, FerbLink.REWRITE_TIMEOUT_MS, ContentRewrite.SCHEMA),
            "rewrite (no schema)" to FerbLink.rewriteExtras("wake me up at 7", ContentRewrite.PROMPT_A, ContentRewrite.MAX_TOKENS_A, FerbLink.REWRITE_TIMEOUT_MS, null),
            "transcribe" to FerbLink.transcribeExtras(byteArrayOf(1, 2, 3), "audio/mp4", FerbLink.TRANSCRIBE_TIMEOUT_MS),
        )
        fun kind(v: Any?) = when (v) {
            is String -> "text"
            is Int, is Long -> "number"
            is ByteArray -> "bytes"
            // A fixture writes bytes as {"bytes": …}.
            is JSONObject -> if (v.has("bytes")) "bytes" else "object"
            else -> v?.javaClass?.simpleName ?: "null"
        }
        sent.forEach { (label, extras) ->
            val method = label.substringBefore(" (")
            val examples = of(method).map { it.extras }
            assertThat(examples).isNotEmpty()
            val taken = examples.flatMap { e -> e.keys().asSequence().map { it to kind(e.get(it)) }.toList() }.toMap()
            extras.forEach { (key, value) ->
                assertWithMessage("$label: $key").that(taken).containsKey(key)
                assertWithMessage("$label: $key's kind").that(kind(value)).isEqualTo(taken[key])
            }
            val always = examples.map { it.keys().asSequence().toSet() }.reduce { x, y -> x intersect y }
            assertWithMessage("$label: keys every example sends").that(extras.keys).containsAtLeastElementsIn(always)
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
                    jev = r.optBoolean("jev"), jevCapped = r.optString("jev_off_reason") == "cap", jsonSchema = r.optBoolean("json_schema")))
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
    fun jevsCapIsReadFromItsOwnSignal() {
        // ferb-link d588b50: decide says when the cap is why (jev_capped), at the cap and near it.
        fun fixture(name: String) = FerbLink.decisionOf(values(cases.single { it.name == name }.result))
        val cap = fixture("decide-cap")
        val near = fixture("decide-near-cap")
        assertThat(listOf(cap.capped, near.capped)).containsExactly(true, true)
        assertThat(fixture("decide-off").capped).isNotEqualTo(true)
        // The reason, Ferb Link's own words with Jev named once; near the cap it isn't "reached".
        assertThat((LinkPolicy.select(cap, listOf("set_alarm")) as JevPick.Fallback).reason).isEqualTo("Jev's limit reached")
        assertThat((LinkPolicy.select(near, listOf("set_alarm")) as JevPick.Fallback).reason).isEqualTo("Jev near its spending limit")
        // The details: Ferb Link's own, which say what to do.
        assertThat(LinkPolicy.jevLine(cap)).contains("Raise it in Ferb Link")
        assertThat(LinkPolicy.jevLine(near)).contains("a shorter one may still go")
        // An older Ferb Link, without the signal: the prose still tells.
        val old = JevReply(null, emptyMap(), error = "Jev's limit reached", errorDetail = "Jev reached its spending limit (\$3.00).", errorKind = "off")
        assertThat(old.capped).isNull()
        assertThat(LinkPolicy.jevLine(old)).contains("spending limit")
    }

    @Test
    fun jevsFallbackReasonNamesJevOnce() {
        // ferb-link P56: the chat's reason was "Jev " + Ferb Link's short, so "Jev off" read "Jev Jev off".
        val failed = of("decide").map { FerbLink.decisionOf(values(it.result)) }.filter { it.p == null }
        assertThat(failed).isNotEmpty()
        for (jev in failed) {
            val reason = (LinkPolicy.select(jev, listOf("set_alarm")) as JevPick.Fallback).reason
            assertWithMessage(reason).that(Regex("""(?i)\bjev\b""").findAll(reason).count()).isEqualTo(1)
        }
        // A short without Jev's name (as near the cap) still reads with it once.
        val near = JevReply(null, emptyMap(), error = "near its spending limit", errorDetail = "Jev is near its spending limit (\$2.90 of \$3.00)", errorKind = "off")
        assertThat((LinkPolicy.select(near, listOf("set_alarm")) as JevPick.Fallback).reason).isEqualTo("Jev near its spending limit")
        // At the cap: the limit, named once.
        val cap = failed.single { it.errorDetail?.startsWith("Jev reached its spending limit") == true }
        assertThat((LinkPolicy.select(cap, listOf("set_alarm")) as JevPick.Fallback).reason).isEqualTo("Jev's limit reached")
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
        // One command, and none (two commands and invented facts were the v2 prompt's, now the bench's).
        assertThat(all.map { it.commands.size }).containsAtLeast(0, 1)
    }

    @Test
    fun anInstructedRewriteIsReadAsGeminiNanosIs() {
        // Ferb's own prompt on the computer: its raw answer, read by Ferb's own rules.
        val instructed = of("rewrite").filter { it.extras.has(LinkContract.EXTRA_INSTRUCTIONS) }
        assertThat(instructed).isNotEmpty()
        val replies = instructed.associate { it.name to FerbLink.rewriteOf(values(it.result)) }
        val one = replies.getValue("rewrite-gemini")
        assertThat(one.instructed).isTrue()
        assertThat(dev.abhay.assistant.voice.GeminiRewrite.parse("open the nfc settings", one.raw!!).command).isEqualTo("Open NFC settings")
        val none = replies.getValue("rewrite-gemini-none")
        assertThat(dev.abhay.assistant.voice.GeminiRewrite.parse("the alarm woke the baby", none.raw!!).command).isNull()
        // Failures say so, and still echo the prompt: a missing echo always means an older Ferb Link.
        listOf("rewrite-gemini-bad_reply", "rewrite-gemini-bad_request", "rewrite-gemini-cut", "rewrite-gemini-too_long").forEach {
            assertShort(replies.getValue(it).error)
            assertThat(replies.getValue(it).instructed).isTrue()
        }
        // Ferb's prompt is well under Ferb Link's 16000-character limit on instructions.
        assertThat(dev.abhay.assistant.voice.GeminiRewrite.PROMPT.length).isLessThan(16_000)
        // Cut off at max_tokens (2026-10-24): a failure, with the half-written raw kept but not a command.
        replies.getValue("rewrite-gemini-cut").let {
            assertThat(it.commands).isEmpty()
            assertThat(it.raw).isNotEmpty()
        }
        // An older Ferb Link answers with its own prompt, without the echo or raw: the step falls back.
        val old = replies.getValue("rewrite-gemini-old")
        assertThat(old.instructed).isFalse()
        assertThat(old.raw).isNull()
    }

    @Test
    fun statusSaysWhetherTheRewriteTakesAJsonSchema() {
        val status = { name: String -> FerbLink.statusOf(values(cases.single { it.name == name }.result)) as LinkStatus.Ready }
        assertThat(status("status").jsonSchema).isTrue()
        // An older Ferb Link doesn't say: Ferb uses its line prompt there.
        assertThat(status("status-no-json-schema").jsonSchema).isFalse()
    }

    @Test
    fun aRewriteWithAJsonSchemaIsItsOutputOrAShortError() {
        val replies = of("rewrite").filter { it.extras.has(LinkContract.EXTRA_JSON_SCHEMA) }.associate { it.name to FerbLink.rewriteOf(values(it.result)) }
        assertThat(replies).isNotEmpty()
        replies.getValue("rewrite-json").let {
            assertThat(it.error).isNull()
            assertThat(it.schema).isTrue()
            assertThat(it.output!!.getString("command")).isEqualTo("Send a text to Rahul")
            assertThat(it.output!!.getString("content")).isEqualTo("kal shaam ko milte hain")
        }
        // Cut off: always a failure with a schema, the half-written answer kept but no output.
        replies.getValue("rewrite-json-cut").let {
            assertShort(it.error)
            assertThat(it.truncated).isTrue()
            assertThat(it.output).isNull()
        }
        // The server can't use the schema: a failure Ferb tells apart, to use its line prompt.
        assertThat(replies.getValue("rewrite-json-unsupported").schemaUnsupported).isTrue()
        listOf("rewrite-json-cut", "rewrite-json-not-json", "rewrite-json-too_long").forEach { assertThat(replies.getValue(it).schemaUnsupported).isFalse() }
        // An older Ferb Link ignores the schema: no "schema" in its reply, so Ferb treats it as unsupported.
        replies.getValue("rewrite-json-old").let {
            assertThat(it.error).isNull()
            assertThat(it.schema).isFalse()
            assertThat(it.output).isNull()
        }
        replies.filterKeys { it != "rewrite-json" && it != "rewrite-json-old" }.values.forEach { assertShort(it.error) }
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

    private companion object {
        const val LIMITS = "limits.json"
    }
}

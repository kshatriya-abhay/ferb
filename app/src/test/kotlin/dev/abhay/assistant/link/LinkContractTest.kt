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
        cases.filter { !it.result.has("throws") }.forEach { assertThat(it.method).isIn(listOf("status", "check", "complete", "transcribe", "wake")) }
        listOf("status", "check", "complete", "transcribe", "wake").forEach { assertThat(of(it)).isNotEmpty() }
    }

    @Test
    fun ferbSendsWhatFerbLinkTakes() {
        // Every key Ferb puts in a call's extras is one Ferb Link's examples take (some take more,
        // optional ones Ferb doesn't send, such as facts).
        val sent = mapOf(
            "complete" to setOf(LinkContract.EXTRA_REQUEST, LinkContract.EXTRA_TOOLS, LinkContract.EXTRA_TIMEOUT_MS),
            "transcribe" to setOf(LinkContract.EXTRA_AUDIO, LinkContract.EXTRA_AUDIO_MIME, LinkContract.EXTRA_TIMEOUT_MS),
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
                else -> assertThat(status).isEqualTo(LinkStatus.Ready(r.optString("model").ifBlank { null }, r.optBoolean("audio")))
            }
        }
        // A ready Ferb Link names its model, and whether it takes audio (the mic needs it).
        val ready = of("status").map { FerbLink.statusOf(values(it.result)) }.filterIsInstance<LinkStatus.Ready>()
        assertThat(ready).isNotEmpty()
        ready.forEach { assertThat(it.model).isNotNull() }
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

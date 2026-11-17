package dev.abhay.assistant.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

class NeedleReplyTest {
    /** Real engine output, from the Linux CLI with these tools. */
    private val twoCalls = """{"type":"call","success":true,"error":null,"error_code":null,"reason":null,
        "function_calls":[{"name":"set_text_color","arguments":{"hex":"#3F51B5"}},{"name":"set_text_size","arguments":{"size_sp":48}}],
        "suppressed_calls":[],"reasoning":"'#3F51B5' -> hex; 'size 48' -> size_sp 48","confidence":0.8487,
        "prefill_tps":851.1,"decode_tps":723.8,"peak_ram_mb":79.3,"validation":{"ungrounded":[],"negation":false}}"""

    private val refused = """{"type":"call","success":true,"function_calls":[],
        "suppressed_calls":[{"name":"show_notification","arguments":{"message":"Weather"}}],
        "reasoning":"No weather tool.","confidence":0.0396,"prefill_tps":1.0,"decode_tps":1.0,"peak_ram_mb":79.0,
        "validation":{"ungrounded":["show_notification.message"],"negation":false}}"""

    /** org.json as Android has it: optString on a JSON null gives the text "null" (the JVM's gives ""). */
    private class AndroidJson(json: String) : org.json.JSONObject(json) {
        override fun optString(name: String?): String = if (has(name) && isNull(name)) "null" else super.optString(name)
    }

    @Test
    fun aNullFromTheEngineIsNoText() {
        // Review P2-92: an engine failure with "error": null read "Needle 3: null" on the phone.
        val reply = NeedleReply.parse(AndroidJson("""{"success":false,"error":null,"reasoning":null,"function_calls":[]}"""))
        assertThat(reply.error).isEqualTo("Engine error")
        assertThat(reply.reasoning).isEmpty()
        // A real message still reads as itself.
        assertThat(NeedleReply.parse(AndroidJson("""{"success":false,"error":"model not loaded"}""")).error).isEqualTo("model not loaded")
    }

    @Test
    fun parsesCallsConfidenceAndTimings() {
        val reply = NeedleReply.parse(twoCalls)
        assertThat(reply.calls.map { it.signature() })
            .containsExactly("set_text_color(hex = \"#3F51B5\")", "set_text_size(size_sp = 48)").inOrder()
        assertThat(reply.confidence).isWithin(1e-9).of(0.8487)
        assertThat(reply.decodeTps).isWithin(1e-9).of(723.8)
        assertThat(reply.error).isNull()
        assertThat(Decision.of(reply)).isEqualTo(Decision.Run)
    }

    @Test
    fun suppressedCallsAreKept() {
        val reply = NeedleReply.parse(refused)
        assertThat(reply.calls).isEmpty()
        assertThat(reply.suppressed.single().name).isEqualTo("show_notification")
        assertThat(Decision.of(reply)).isEqualTo(Decision.Refuse)
    }

    @Test
    fun midConfidenceAsksFirst() {
        val reply = NeedleReply.parse(twoCalls.replace("0.8487", "0.35"))
        assertThat(Decision.of(reply)).isEqualTo(Decision.Confirm)
        assertThat(Decision.of(reply.copy(confidence = null))).isEqualTo(Decision.Confirm)
    }

    @Test
    fun theBarCanBeLowered() {
        val reply = NeedleReply.parse(twoCalls.replace("0.8487", "0.45"))
        assertThat(Decision.of(reply)).isEqualTo(Decision.Confirm)
        assertThat(Decision.of(reply, runAt = 0.4)).isEqualTo(Decision.Run)
        assertThat(Decision.of(NeedleReply.parse(refused), runAt = 0.0)).isEqualTo(Decision.Refuse)
    }

    @Test
    fun engineFailureIsReported() {
        assertThat(NeedleReply.parse("""{"success":false,"error":"context overflow"}""").error).isEqualTo("context overflow")
    }

    @Test
    fun systemFactsAreFacts() {
        assertThat(AssistantEngine.systemFacts(LocalDate.of(2026, 10, 5), Locale.forLanguageTag("en-IN")))
            .isEqualTo("date: 2026-10-05 (Monday)\nlocale: en-IN\ndevice: Android phone")
    }
}

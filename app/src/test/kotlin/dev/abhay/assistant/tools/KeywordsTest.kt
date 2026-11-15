package dev.abhay.assistant.tools

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class KeywordsTest {
    private fun matches(words: List<String>, request: String) = Keywords.regex(words)!!.containsMatchIn(request)

    @Test
    fun aDevanagariKeywordMatchesOnlyAsAWholeWord() {
        // Review P2-9: "whole word" leaned on each engine's \w, which on the JVM is ASCII only, so a
        // Hindi keyword matched inside a longer word there but not in tool-check's Python.
        val remind = Keywords.regex(listOf("याद दिला"))!!
        assertThat(remind.containsMatchIn("मुझे याद दिला देना")).isTrue()
        assertThat(remind.containsMatchIn("मैं तुम्हें याद दिलाऊँगा")).isFalse()
        // A vowel sign continues the word too ("दिल", heart, isn't in "दिला").
        assertThat(Keywords.regex(listOf("दिल"))!!.containsMatchIn("याद दिला")).isFalse()
        assertThat(Keywords.regex(listOf("alarm"))!!.containsMatchIn("alarms, please")).isTrue()
        assertThat(Keywords.regex(listOf("alarm"))!!.containsMatchIn("alarmé")).isFalse()
    }

    @Test
    fun wordsMatchWholeAndWithAnS() {
        assertThat(matches(listOf("alarm"), "Show my Alarms")).isTrue()
        assertThat(matches(listOf("pic"), "send a picture")).isFalse()
        assertThat(matches(listOf("light"), "flashlight on")).isFalse()
    }

    @Test
    fun phrasesMatchAcrossAnySpacing() {
        assertThat(matches(listOf("get up"), "I need to get  up at 6")).isTrue()
        assertThat(matches(listOf("get up"), "get me up")).isFalse()
    }

    @Test
    fun punctuationIsLiteral() {
        assertThat(matches(listOf("wi-fi"), "open Wi-Fi settings")).isTrue()
        assertThat(matches(listOf("c++"), "open the c++ docs")).isTrue()
        assertThat(matches(listOf("a.b"), "axb")).isFalse()
    }

    @Test
    fun cleanTrimsLowersAndRejectsEmptyOrLong() {
        assertThat(Keywords.clean("  Flash   Light ")).isEqualTo("flash light")
        assertThat(Keywords.clean("   ")).isNull()
        assertThat(Keywords.clean("x".repeat(Keywords.MAX_LENGTH + 1))).isNull()
    }

    @Test
    fun noWordsMatchNothing() {
        assertThat(Keywords.regex(emptyList())).isNull()
        assertThat(Keywords.regex(listOf(" "))).isNull()
    }

    @Test
    fun everyToolHasKeywords() {
        Tools.all.forEach { tool -> assertThat(tool.keywords).isNotEmpty() }
        Tools.all.forEach { tool -> tool.keywords.forEach { assertThat(Keywords.clean(it)).isEqualTo(it) } }
    }
}

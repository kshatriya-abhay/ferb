package dev.abhay.assistant.tools

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class KeywordsTest {
    private fun matches(words: List<String>, request: String) = Keywords.regex(words)!!.containsMatchIn(request)

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

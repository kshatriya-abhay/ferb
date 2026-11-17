package dev.abhay.assistant

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * Review P2-185: no log call takes what the user said, heard or wrote, except through Private,
 * which logs only its length (unless the debug build is opted in for the run). A source scan of
 * every Log call in the app's code: each `$name` or `${…}` it interpolates is checked against the
 * names user text goes by.
 */
class PrivateLogsTest {
    /** The names a request's words, a transcript, a rewrite or a note's text go by in Ferb's code. */
    private val userText = Regex("""\b(said|heard|request|command|content|transcript|text|title|query|message|words|spoken|typed|note|name)\b""")

    /** Each Log call in [source], as one string from `Log.` to its closing parenthesis. */
    private fun logCalls(source: String): List<String> = Regex("""\bLog\.[vdiwe]\(""").findAll(source).map { m ->
        var depth = 0
        var end = m.range.last
        while (end < source.length) {
            when (source[end]) { '(' -> depth++; ')' -> if (--depth == 0) break }
            end++
        }
        source.substring(m.range.first, minOf(end + 1, source.length))
    }.toList()

    /** Matches the names but aren't user text: a bench file's name. */
    private val notUserText = setOf("file.name")

    /** The interpolations in [call]'s string templates that aren't wrapped in Private.text. */
    private fun leaks(call: String): List<String> {
        val exprs = Regex("""\$\{((?:[^{}]|\{[^{}]*\})*)\}|\$([a-zA-Z_][a-zA-Z0-9_.]*)""").findAll(call).map { it.groupValues[1].ifEmpty { it.groupValues[2] } }
        return exprs.filter { e -> !e.trimStart().startsWith("Private.text(") && e.trim() !in notUserText && userText.containsMatchIn(e) }.toList()
    }

    @Test
    fun noLogCallTakesUserTextExceptThroughPrivate() {
        val sources = listOf("src/main/kotlin", "src/debug/kotlin", "src/release/kotlin").map(::File).flatMap { dir ->
            dir.walkTopDown().filter { it.extension == "kt" }.toList()
        }
        assertThat(sources).isNotEmpty()
        val found = sources.flatMap { f -> logCalls(f.readText()).flatMap { call -> leaks(call).map { "${f.name}: $it" } } }
        assertThat(found).isEmpty()
    }

    @Test
    fun theScanCatchesALeak() {
        // The scan itself: a raw request in a log call is caught; through Private it isn't.
        assertThat(logCalls("""Log.i(TAG, "heard ${'$'}{transcript.heard} in ${'$'}ms ms")""").flatMap(::leaks)).containsExactly("transcript.heard")
        assertThat(logCalls("""Log.i(TAG, "heard ${'$'}{Private.text(transcript.heard)}")""").flatMap(::leaks)).isEmpty()
        assertThat(logCalls("""Log.i(TAG, "rewrite: ${'$'}said ->
            ${'$'}{answer.command}")""").flatMap(::leaks)).containsExactly("said", "answer.command")
    }
}

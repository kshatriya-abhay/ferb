package dev.abhay.assistant

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/** The release's notes come from CHANGELOG.md's section for the version (scripts/changelog-section.sh, used by publish.sh). */
class ReleaseNotesTest {
    private val repo = File("..").canonicalFile

    private fun section(version: String): Pair<Int, String> {
        val p = ProcessBuilder("bash", "scripts/changelog-section.sh", version).directory(repo).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        return p.waitFor() to out
    }

    @Test
    fun theVersionBeingBuiltHasItsNotes() {
        val version = Regex("""versionName = "(.+)"""").find(File(repo, "app/build.gradle.kts").readText())!!.groupValues[1]
        val (exit, notes) = section(version)
        assertThat(exit).isEqualTo(0)
        assertThat(notes.trim()).isNotEmpty()
        // The section only: no heading of its own or of the next version's, subheadings kept.
        assertThat(notes).doesNotContain("## $version\n")
        assertThat(notes.lines().none { it.startsWith("## ") }).isTrue()
    }

    @Test
    fun aVersionWithNoSectionIsRefused() {
        assertThat(section("9.9.9").first).isNotEqualTo(0)
    }
}

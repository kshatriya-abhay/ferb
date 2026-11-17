package dev.abhay.assistant

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/** The release's notes come from CHANGELOG.md's section for the version (scripts/changelog-section.sh, used by publish.sh). */
class ReleaseNotesTest {
    private val repo = File("..").canonicalFile

    private fun section(version: String, file: File? = null): Pair<Int, String> {
        val p = ProcessBuilder(listOfNotNull("bash", "scripts/changelog-section.sh", version, file?.path)).directory(repo).redirectErrorStream(true).start()
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

    @Test
    fun theSectionsEdges() {
        // As Ferb Link's tests have them (its release/changelog-section.sh reads the same way).
        val sample = File.createTempFile("changelog", ".md").apply {
            deleteOnExit()
            writeText("# Changelog\n\n## 0.10\n\n- ten\n\n## 0.3\n## 0.2\n\n### Group\n\n- two\n\n\n## 0.1\n\n- one\n")
        }
        assertThat(section("0.2", sample)).isEqualTo(0 to "### Group\n\n- two\n")
        assertThat(section("0.1", sample)).isEqualTo(0 to "- one\n") // the last runs to the end; not "## 0.10"
        assertThat(section("0.3", sample).first).isNotEqualTo(0) // empty
        assertThat(section("0.4", sample).first).isNotEqualTo(0) // missing
    }
}

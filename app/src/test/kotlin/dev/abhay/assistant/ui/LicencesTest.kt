package dev.abhay.assistant.ui

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/** The licences page lists what the README credits as shipped, and Apache-2.0's text is bundled. */
class LicencesTest {
    @Test
    fun everyShippedCreditIsListed() {
        val readme = File("../README.md").readText().substringAfter("## Credits").substringBefore("## License")
        val shipped = readme.lines().filter { it.startsWith("| [") && "not shipped" !in it }
            .map { it.substringAfter("[").substringBefore("]") }
        assertThat(shipped).isNotEmpty()
        val names = LICENCES.map { it.name }
        assertThat(shipped.filterNot { credit -> names.any { credit.startsWith(it) } }).isEmpty()
    }

    @Test
    fun apacheTextIsBundled() {
        val bundled = File("src/main/res/raw/apache_2_0.txt").readText()
        assertThat(bundled).isEqualTo(File("../LICENSE").readText())
        assertThat(paragraphs(bundled).filter { '\n' in it }).isEmpty()
    }

    @Test
    fun paragraphsAreJoinedIntoSingleLines() {
        val text = "  1. Definitions.\n\n      \"License\" shall mean the terms\n      and conditions.\n\n\n   2. Grant.\n"
        assertThat(paragraphs(text)).containsExactly("1. Definitions.", "\"License\" shall mean the terms and conditions.", "2. Grant.").inOrder()
    }
}

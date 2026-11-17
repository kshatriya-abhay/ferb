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

    /** Which credit covers a shipped library (group, or group:artifact prefix). */
    private val creditFor = listOf(
        "androidx." to "AndroidX",
        "com.google.android.material:" to "Material Components for Android",
        "com.google.mlkit:" to "ML Kit GenAI",
        "org.jetbrains.kotlinx:kotlinx-coroutines" to "Kotlin coroutines",
        "org.jetbrains.kotlinx:kotlinx-serialization" to "kotlinx.serialization",
        "org.jetbrains.kotlin:" to "Kotlin standard library",
        "com.google.guava:" to "Guava",
        "com.google.android.gms:" to "Google Play services",
        "com.google.firebase:" to "Firebase components and Google Data Transport",
        "com.google.android.datatransport:" to "Firebase components and Google Data Transport",
        "com.google.code.findbugs:" to "Annotations",
        "com.google.errorprone:" to "Annotations",
        "org.jspecify:" to "Annotations",
        "javax.inject:" to "Annotations",
        "org.jetbrains:annotations" to "Annotations",
        "com.google.j2objc:" to "Annotations",
        "org.checkerframework:" to "Checker Framework qualifiers",
        "org.reactivestreams:" to "Reactive Streams",
    )

    @Test
    fun everyLibraryTheReleaseShipsIsCredited() {
        // Review P2-152: Credits left out what ML Kit, Guava and Kotlin bring. The list is the release's
        // runtime classpath (`mise run credits:sync` after changing a dependency).
        val shipped = File("src/test/resources/shipped-dependencies.txt").readLines().filter { it.isNotBlank() }
        assertThat(shipped).isNotEmpty()
        val names = LICENCES.map { it.name }
        val uncredited = shipped.filter { lib ->
            val credit = creditFor.firstOrNull { (prefix, _) -> lib.startsWith(prefix) }?.second
            credit == null || names.none { it.startsWith(credit) }
        }
        assertThat(uncredited).isEmpty()
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

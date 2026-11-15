package dev.abhay.assistant.link

import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import java.security.MessageDigest
import org.junit.Test

/**
 * Ferb's copy of Ferb Link's contract fixtures is the one Ferb Link wrote: each fixture matches
 * the copy's SHA256SUMS (Ferb Link's, in sha256sum format), and, when Ferb Link's repo is beside
 * this one (or at FERB_LINK), that SHA256SUMS is still Ferb Link's. Either failing means
 * `mise run link:contract-sync`.
 */
class LinkContractSyncTest {
    private val copy = File(javaClass.classLoader!!.getResource("ferb-link-contract/v1")!!.toURI())
    private val fix = "Ferb's copy of Ferb Link's contract is out of date: run mise run link:contract-sync"

    private fun sums(file: File): Map<String, String> = file.readLines().filter { it.isNotBlank() }.associate { line ->
        val (sha, name) = line.trim().split(Regex("""\s+\*?"""), limit = 2)
        name to sha
    }

    private fun sha256(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    @Test
    fun theCopyMatchesItsChecksums() {
        val listed = File(copy, "SHA256SUMS")
        assertWithMessage("$fix (no SHA256SUMS)").that(listed.exists()).isTrue()
        val sums = sums(listed)
        val fixtures = copy.listFiles { f -> f.extension == "json" }!!.associate { it.name to sha256(it) }
        assertWithMessage("$fix (fixtures not in SHA256SUMS, or listed but missing)").that(fixtures.keys).isEqualTo(sums.keys)
        fixtures.forEach { (name, sha) -> assertWithMessage("$fix ($name changed)").that(sha).isEqualTo(sums[name]) }
    }

    @Test
    fun theCopyIsFerbLinksLatest() {
        val root = System.getenv("FERB_LINK")?.let(::File) ?: File("..").canonicalFile.resolve("../ferb-link")
        val theirs = File(root, "contract/v1/SHA256SUMS")
        if (!theirs.exists()) return // Ferb Link's repo isn't here (CI): the copy's own check above still runs.
        assertWithMessage("$fix (Ferb Link's SHA256SUMS at $theirs differs)")
            .that(File(copy, "SHA256SUMS").takeIf { it.exists() }?.readText()).isEqualTo(theirs.readText())
    }
}

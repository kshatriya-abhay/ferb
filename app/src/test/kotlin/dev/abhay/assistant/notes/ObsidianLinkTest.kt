package dev.abhay.assistant.notes

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** [ObsidianLink]: a note's document URI to `obsidian://open?vault=…&file=…`. */
class ObsidianLinkTest {
    private val storage = "content://com.android.externalstorage.documents"

    @Test
    fun theFolderIsTheVaultAndTheNoteItsFile() {
        // The form checked on the Pixel 11: no .md, spaces as %20.
        val uri = "$storage/tree/primary%3ADocuments%2FPersonal%20Vault/document/primary%3ADocuments%2FPersonal%20Vault%2Fbuy%20milk.md"
        assertThat(ObsidianLink.of(uri, "buy milk")).isEqualTo("obsidian://open?vault=Personal%20Vault&file=buy%20milk")
    }

    @Test
    fun aNoteInASubfolderKeepsItsPathFromTheVault() {
        val uri = "$storage/tree/primary%3AV/document/primary%3AV%2FLists%2FShopping.md"
        assertThat(ObsidianLink.of(uri, "Shopping")).isEqualTo("obsidian://open?vault=V&file=Lists%2FShopping")
    }

    @Test
    fun oddNamesAreEncoded() {
        // Hindi, "+", "&", "#" and "%"; "+" in a file name stays a plus.
        val uri = "$storage/tree/primary%3AV/document/primary%3AV%2F%E0%A4%A6%E0%A5%82%E0%A4%A7%20+%20%26%20%23%2025%25.md"
        assertThat(ObsidianLink.of(uri, "x")).isEqualTo("obsidian://open?vault=V&file=%E0%A4%A6%E0%A5%82%E0%A4%A7%20%2B%20%26%20%23%2025%25")
    }

    @Test
    fun otherProvidersUseTheNotesName() {
        val uri = "content://com.example.sync.documents/tree/root%3AMy%20Vault/document/abc123"
        assertThat(ObsidianLink.of(uri, "Shopping list")).isEqualTo("obsidian://open?vault=My%20Vault&file=Shopping%20list")
    }

    @Test
    fun notADocumentIsFerbsViewer() {
        assertThat(ObsidianLink.of("file:///sdcard/a.md", "a")).isNull()
        assertThat(ObsidianLink.of("$storage/tree/primary%3AV", "a")).isNull()
        assertThat(ObsidianLink.of("$storage/tree/primary%3A/document/primary%3Aa.md", "a")).isNull()
    }
}

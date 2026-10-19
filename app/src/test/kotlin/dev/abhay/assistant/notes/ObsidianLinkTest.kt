package dev.abhay.assistant.notes

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** [ObsidianLink]: a note's document URI to the `obsidian://open` link, in the order tried. */
class ObsidianLinkTest {
    private val storage = "content://com.android.externalstorage.documents"

    @Test
    fun aNoteOnThePhonesStorageOpensByItsPath() {
        val uri = "$storage/tree/primary%3ADocuments%2FMy%20Vault/document/primary%3ADocuments%2FMy%20Vault%2FShopping%20list.md"
        assertThat(ObsidianLink.of(uri, "Shopping list"))
            .isEqualTo("obsidian://open?path=%2Fstorage%2Femulated%2F0%2FDocuments%2FMy%20Vault%2FShopping%20list.md")
    }

    @Test
    fun aFolderInsideTheVaultStillOpensByPath() {
        val uri = "$storage/tree/primary%3AVault%2FFerb/document/primary%3AVault%2FFerb%2Fa.md"
        assertThat(ObsidianLink.of(uri, "a")).isEqualTo("obsidian://open?path=%2Fstorage%2Femulated%2F0%2FVault%2FFerb%2Fa.md")
    }

    @Test
    fun sdCardsAndOddNames() {
        assertThat(ObsidianLink.path("com.android.externalstorage.documents", "1234-ABCD:Notes/x.md")).isEqualTo("/storage/1234-ABCD/Notes/x.md")
        assertThat(ObsidianLink.path("com.android.externalstorage.documents", "home:x.md")).isEqualTo("/storage/emulated/0/Documents/x.md")
        assertThat(ObsidianLink.path("com.android.externalstorage.documents", "weird:x.md")).isNull()
        // Hindi, "+", "&", "#" and "%" are encoded; "+" in a file name stays a plus.
        val uri = "$storage/tree/primary%3AV/document/primary%3AV%2F%E0%A4%A6%E0%A5%82%E0%A4%A7%20+%20%26%20%23%2025%25.md"
        assertThat(ObsidianLink.of(uri, "x")).isEqualTo(
            "obsidian://open?path=%2Fstorage%2Femulated%2F0%2FV%2F%E0%A4%A6%E0%A5%82%E0%A4%A7%20%2B%20%26%20%23%2025%25.md")
    }

    @Test
    fun otherProvidersOpenByVaultAndName() {
        val uri = "content://com.example.sync.documents/tree/root%3AMy%20Vault/document/abc123"
        assertThat(ObsidianLink.of(uri, "Shopping list")).isEqualTo("obsidian://open?vault=My%20Vault&file=Shopping%20list")
    }

    @Test
    fun notADocumentIsFerbsViewer() {
        assertThat(ObsidianLink.of("file:///sdcard/a.md", "a")).isNull()
        assertThat(ObsidianLink.of("$storage/tree/primary%3AV", "a")).isNull()
    }
}

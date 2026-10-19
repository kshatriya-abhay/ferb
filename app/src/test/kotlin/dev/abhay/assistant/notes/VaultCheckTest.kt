package dev.abhay.assistant.notes

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** [VaultCheck]: `.obsidian` looked up by name in the picked folder, and what Ferb says. */
class VaultCheckTest {
    private val storage = "com.android.externalstorage.documents"

    @Test
    fun theEntryIsLookedUpByItsExactName() {
        assertThat(VaultCheck.entryId(storage, "primary:Documents/My Vault")).isEqualTo("primary:Documents/My Vault/.obsidian")
        // The volume's root picked: no slash after the colon.
        assertThat(VaultCheck.entryId(storage, "primary:")).isEqualTo("primary:.obsidian")
        assertThat(VaultCheck.entryId(storage, "1234-ABCD:Vault")).isEqualTo("1234-ABCD:Vault/.obsidian")
        // Providers whose ids aren't paths can't be asked by name.
        assertThat(VaultCheck.entryId("com.example.sync.documents", "root:Vault")).isNull()
        assertThat(VaultCheck.entryId(storage, "weird")).isNull()
    }

    @Test
    fun aWarningOnlyWhenItsMissing() {
        assertThat(VaultCheck.warning(VaultCheck.outcome(true))).isNull()
        assertThat(VaultCheck.warning(VaultCheck.outcome(false)))
            .isEqualTo("This folder doesn't look like an Obsidian vault. Pick the vault's top folder so notes open in Obsidian.")
        // Couldn't tell (another provider, no access): nothing said.
        assertThat(VaultCheck.warning(VaultCheck.outcome(null))).isNull()
    }
}

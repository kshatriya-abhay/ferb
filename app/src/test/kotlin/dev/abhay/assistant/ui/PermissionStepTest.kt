package dev.abhay.assistant.ui

import android.Manifest
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Review P2-123, P2-138: one rule for what a permission ask does. */
class PermissionStepTest {
    private val contacts = Manifest.permission.READ_CONTACTS
    private val calls = Manifest.permission.CALL_PHONE

    @Test
    fun grantedGoesMissingAsksRefusedForGoodOpensSettings() {
        assertThat(PermissionStep.of(listOf(contacts), { true }, emptySet())).isEqualTo(PermissionStep.Go)
        assertThat(PermissionStep.of(listOf(contacts, calls), { it == contacts }, emptySet())).isEqualTo(PermissionStep.Ask(listOf(calls)))
        assertThat(PermissionStep.of(listOf(calls), { false }, setOf(calls))).isEqualTo(PermissionStep.OpenSettings)
    }

    @Test
    fun aPermissionGrantedInSettingsSinceGoesAhead() {
        // A reply's Allow checked "refused" first, and sent the user back to settings after they'd granted it there.
        assertThat(PermissionStep.of(listOf(contacts), { true }, setOf(contacts))).isEqualTo(PermissionStep.Go)
    }

    @Test
    fun oneRefusalIsntFinalWhileAndroidWouldAskAgain() {
        // Android 11+ asks twice: a refusal with a rationale still to show isn't for good.
        val result = mapOf(contacts to false, calls to false)
        assertThat(PermissionStep.refusedForGood(result) { it == contacts }).containsExactly(calls)
        assertThat(PermissionStep.refusedForGood(mapOf(contacts to true)) { false }).isEmpty()
    }
}

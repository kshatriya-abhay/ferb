package dev.abhay.assistant.ui

import android.app.Activity
import android.content.pm.PackageManager
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * What to do for permissions a step needs: one rule for a reply's Allow, both mics, a tool's page
 * and the reminder form (review P2-123; they were written four ways, and a reply's Allow sent the
 * user to Android's settings even after they'd granted it there).
 */
sealed interface PermissionStep {
    /** All granted: go ahead. */
    data object Go : PermissionStep
    /** Android's dialog, for these. */
    data class Ask(val missing: List<String>) : PermissionStep
    /** Android won't show its dialog again for one of them: only its settings can grant it. */
    data object OpenSettings : PermissionStep

    companion object {
        /** [refused]: refused for good (see [refusedForGood]); one granted since counts as granted. */
        fun of(needs: List<String>, granted: (String) -> Boolean, refused: Set<String>): PermissionStep {
            val missing = needs.filterNot(granted)
            return when {
                missing.isEmpty() -> Go
                missing.any { it in refused } -> OpenSettings
                else -> Ask(missing)
            }
        }

        /**
         * Those refused in Android's dialog that it won't ask for again: since Android 11 it asks
         * twice, and only then stops showing a rationale (review P2-138: one refusal wasn't final).
         */
        fun refusedForGood(result: Map<String, Boolean>, rationale: (String) -> Boolean): Set<String> =
            result.filter { (permission, ok) -> !ok && !rationale(permission) }.keys
    }
}

/**
 * Runs [PermissionStep] for the permissions it's given: goes ahead, asks, or opens Ferb's page in
 * Android's settings. [onResult] gets those granted now, of the ones it was given or asked (not
 * called for settings: back from there, the screen looks again on resume). The refusals it
 * remembers survive a rotation.
 */
@Composable
internal fun rememberPermissionFlow(onResult: (List<String>) -> Unit): PermissionFlow {
    val context = LocalContext.current
    val activity: Activity? = LocalActivity.current
    var refused by rememberSaveable { mutableStateOf(setOf<String>()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        refused = refused + PermissionStep.refusedForGood(result) { activity?.shouldShowRequestPermissionRationale(it) ?: true }
        onResult(result.filterValues { it }.keys.toList())
    }
    fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    return PermissionFlow(
        step = { needs -> PermissionStep.of(needs, ::granted, refused) },
        run = { needs ->
            when (val step = PermissionStep.of(needs, ::granted, refused)) {
                PermissionStep.Go -> onResult(needs)
                is PermissionStep.Ask -> launcher.launch(step.missing.toTypedArray())
                PermissionStep.OpenSettings -> openAppSettings(context)
            }
        },
    )
}

/** [step]: what [run] would do now, for a button's words; [run]: does it. */
internal class PermissionFlow(val step: (List<String>) -> PermissionStep, val run: (List<String>) -> Unit)

package dev.abhay.assistant.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.abhay.assistant.R

/**
 * What Ferb ships with, and under which licence ([LICENCES]): the README's Credits, less what's
 * only used on the computer. Apache-2.0's text is bundled (res/raw/apache_2_0.txt), as Ferb has no
 * internet; other terms open in the browser.
 */
internal data class Licence(val name: String, val by: String, val licence: String, val url: String? = null)

internal const val APACHE = "Apache-2.0"
private const val MLKIT_TERMS = "https://developers.google.com/ml-kit/terms"

internal val LICENCES = listOf(
    Licence("Ferb", "Abhay Kshatriya", APACHE),
    Licence("Needle 3", "Cactus Compute", APACHE),
    Licence("Whistle", "Cactus Compute", APACHE),
    Licence("Needle engine", "Cactus Compute", APACHE),
    Licence("ML Kit GenAI Speech Recognition", "Google", "ML Kit Terms", MLKIT_TERMS),
    Licence("ML Kit GenAI Prompt API", "Google", "ML Kit Terms", MLKIT_TERMS),
    Licence("AndroidX", "Google", APACHE),
    Licence("Material Components for Android", "Google", APACHE),
    Licence("Kotlin coroutines", "JetBrains", APACHE),
    Licence("Material Icons", "Google", APACHE),
)

/** Settings → Open-source licences: each component, and the licence's text or terms on a tap. */
@Composable
fun LicencesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var shown by remember { mutableStateOf<String?>(null) }
    val apache = remember { runCatching { context.resources.openRawResource(R.raw.apache_2_0).bufferedReader().use { it.readText() } }.getOrDefault("") }
    val bar = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(Modifier.nestedScroll(bar.nestedScrollConnection), containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = { TopBar(stringResource(R.string.ui_licences), onBack = onBack, scroll = bar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
            Group(LICENCES.map { item ->
                { shapes ->
                    SegmentedListItem(
                        onClick = {
                            if (item.url != null) context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.url)))
                            else shown = if (shown == item.licence) null else item.licence
                        },
                        shapes = shapes,
                        supportingContent = { Text("${item.by} · ${item.licence}") },
                    ) { Text(item.name) }
                }
            })
            if (shown == APACHE) Text(apache, Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    }
}

/** Settings → Privacy: what stays on the phone, what can leave it, and removing it (PRIVACY.md, shorter). */
@Composable
fun PrivacyScreen(onBack: () -> Unit) {
    val bar = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(Modifier.nestedScroll(bar.nestedScrollConnection), containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = { TopBar(stringResource(R.string.ui_privacy), onBack = onBack, scroll = bar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 8.dp)) {
            Text(stringResource(R.string.privacy_intro), style = MaterialTheme.typography.bodyLarge)
            listOf(
                R.string.privacy_phone_title to R.string.privacy_phone_body,
                R.string.privacy_leaves_title to R.string.privacy_leaves_body,
                R.string.privacy_remove_title to R.string.privacy_remove_body,
            ).forEach { (title, body) ->
                Text(stringResource(title), Modifier.padding(top = 24.dp, bottom = 8.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

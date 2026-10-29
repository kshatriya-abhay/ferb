package dev.abhay.assistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.abhay.assistant.R

/**
 * The first page of the first launch ([OnboardingFlow]): what Ferb is and what stays private. It
 * asks for nothing; permissions come when a tool or the mic needs them.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun WelcomeContent() {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        Surface(shape = MaterialShapes.Cookie9Sided.toShape(), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(112.dp)) {
            Icon(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(10.dp))
        }
        Spacer(Modifier.height(32.dp))
        Text("Ferb does things on your phone", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text("Alarms, timers, apps, settings and the flashlight. Turn on more, such as calls and the calendar, in Settings. Next, choose how you'll talk to it.",
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(painterResource(R.drawable.ic_lock), contentDescription = null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Text("Runs on this phone. No internet.", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

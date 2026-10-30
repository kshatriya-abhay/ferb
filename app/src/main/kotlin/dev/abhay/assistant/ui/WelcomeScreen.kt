package dev.abhay.assistant.ui

import androidx.compose.ui.res.stringResource
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
 * The first page of the first launch ([OnboardingFlow]): what Ferb is (not a chatbot: it does
 * things), the two headline lines alone. It asks for nothing; permissions come when a tool or the mic
 * needs them. Which tools are on comes on the tools page ([Page.Tools]), then [Tip.Switches].
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
        Text(stringResource(R.string.welcome_not_a_chatbot), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.welcome_does_things_for_you), style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
    }
}

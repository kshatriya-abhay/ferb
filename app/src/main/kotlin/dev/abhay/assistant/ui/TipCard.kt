package dev.abhay.assistant.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.abhay.assistant.R
import dev.abhay.assistant.onboarding.Tip

/** A one-time tip, under the thing it explains; a tap anywhere on it, or ✕, puts it away for good. */
@Composable
internal fun TipCard(tip: Tip, onDismiss: () -> Unit, modifier: Modifier = Modifier, action: Pair<String, () -> Unit>? = null) {
    Surface(
        onClick = onDismiss,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = modifier,
    ) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_lightbulb), contentDescription = "Tip", Modifier.padding(end = 8.dp).size(18.dp))
            Text(tip.text, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyMedium)
            action?.let { (label, run) -> TextButton(onClick = { onDismiss(); run() }) { Text(label) } }
            IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Dismiss tip", Modifier.size(18.dp))
            }
        }
    }
}

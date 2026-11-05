package dev.abhay.assistant.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.abhay.assistant.R
import dev.abhay.assistant.pipeline.AudioStep
import dev.abhay.assistant.voice.BatterySaver

/** Whether Battery Saver is on, kept up to date: Android's broadcast while shown, and a check on each resume. */
@Composable
internal fun rememberPowerSave(): Boolean {
    val context = LocalContext.current
    var on by remember { mutableStateOf(BatterySaver.isOn(context)) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) { on = BatterySaver.isOn(c) }
        }
        context.registerReceiver(receiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED), Context.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    LifecycleResumeEffect(context) {
        on = BatterySaver.isOn(context)
        onPauseOrDispose { }
    }
    return on
}

/** Battery Saver is on and Gemini Nano hears spoken requests: it can't, so say so, with the way to its setting. */
@Composable
internal fun BatterySaverBanner(audio: AudioStep, powerSave: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    AnimatedVisibility(BatterySaver.warns(powerSave, audio), modifier, enter = expandVertically(), exit = shrinkVertically()) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.battery_saver_voice), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { BatterySaver.openSettings(context) }) { Text(stringResource(R.string.battery_saver_settings)) }
            }
        }
    }
}

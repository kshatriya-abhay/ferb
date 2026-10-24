package dev.abhay.assistant

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import dev.abhay.assistant.ui.AssistSheet
import dev.abhay.assistant.ui.AssistantViewModel

/**
 * Ferb as the phone's assistant: a translucent screen over the app in front, with the assistant's
 * bottom sheet ([AssistSheet]). Android's assistant role takes an exported activity for
 * ACTION_ASSIST; its screen-context extras aren't read, and no permission is added. Its own task,
 * out of Recents, so the sheet closes back to the app it opened over; the full chat opens in
 * Ferb's own task ([ChatActivity]) with the same conversation.
 */
class AssistActivity : ComponentActivity() {
    /** Each assist invocation, so the sheet listens again (or focuses its box). */
    private val opened = mutableIntStateOf(0)

    private var viewModel: AssistantViewModel? = null

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) viewModel?.leftScreen()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_ASSIST) opened.intValue++
    }

    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) opened.intValue = 1
        setContent {
            val context = LocalContext.current
            val colors = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            MaterialExpressiveTheme(colorScheme = colors, motionScheme = MotionScheme.expressive()) {
                val viewModel: AssistantViewModel = viewModel()
                this@AssistActivity.viewModel = viewModel
                AssistSheet(
                    viewModel,
                    opened = opened.intValue,
                    onOpenChat = {
                        startActivity(Intent(this, ChatActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                        // This screen's request may still be on its way: it finishes once that's answered
                        // (the chat shows it meanwhile, from the same conversation), not before.
                        lifecycleScope.launch {
                            viewModel.state.first { !it.busy && it.voice == null }
                            finish()
                        }
                    },
                    onDismiss = ::finish,
                )
            }
        }
    }
}

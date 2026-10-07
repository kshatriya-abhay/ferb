package dev.abhay.assistant

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.abhay.assistant.ui.AssistantScreen
import dev.abhay.assistant.ui.AssistantViewModel
import dev.abhay.assistant.ui.SettingsScreen
import dev.abhay.assistant.ui.SettingsViewModel
import dev.abhay.assistant.ui.ToolConfigScreen
import dev.abhay.assistant.ui.ToolsScreen

/**
 * The assistant, and its settings: Settings → Tools → one tool's page. The navigation state is a
 * small back stack of screen names ("tool:<name>" for a tool's page).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            // minSdk 33, so the wallpaper-based dynamic colours are always available.
            val context = LocalContext.current
            val colors = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            MaterialExpressiveTheme(colorScheme = colors, motionScheme = MotionScheme.expressive()) {
                val viewModel: AssistantViewModel = viewModel()
                val settings: SettingsViewModel = viewModel()
                var stack by rememberSaveable { mutableStateOf(listOf("assistant")) }
                val open = { screen: String -> stack = stack + screen }
                val back = { stack = stack.dropLast(1) }
                val engine by viewModel.engine.collectAsStateWithLifecycle()
                when (val screen = stack.last()) {
                    "assistant" -> AssistantScreen(viewModel, onOpenSettings = { open("settings") })
                    "settings" -> SettingsScreen(settings, engine, onOpenTools = { open("tools") }, onBack = back)
                    "tools" -> ToolsScreen(settings, onOpenTool = { open("tool:$it") }, onBack = back)
                    else -> ToolConfigScreen(settings, screen.removePrefix("tool:"), onBack = back)
                }
            }
        }
    }
}

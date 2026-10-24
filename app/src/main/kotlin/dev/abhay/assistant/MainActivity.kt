package dev.abhay.assistant

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Ferb's front door (`.MainActivity`, the launcher icon's component, kept by that name): it shows
 * nothing and hands each intent on at once, the assist trigger to the sheet ([AssistActivity]) and
 * everything else to the chat ([ChatActivity]). Android saved this component as the assistant when
 * the role was granted before the sheet existed, and SystemUI starts ACTION_ASSIST at it by name,
 * so it routes rather than shows (2026-10-24). It has a task of its own, out of Recents, so the
 * assist trigger doesn't bring the chat forward over the app in front; no display, no animation.
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = when (Routing.of(intent?.action)) {
            Routing.Sheet -> AssistActivity::class.java
            Routing.Chat -> ChatActivity::class.java
        }
        startActivity(Intent(intent).setClass(this, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }
}

/** Where [MainActivity] hands an intent: the assistant's sheet for the assist trigger, else the chat. */
enum class Routing {
    Chat, Sheet;

    companion object {
        fun of(action: String?) = if (action == Intent.ACTION_ASSIST) Sheet else Chat
    }
}
